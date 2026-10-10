package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectRetrievalRequest;
import com.novelanalyzer.modules.knowledge.vo.ProjectRetrievalResultVO;
import com.novelanalyzer.modules.knowledge.vo.StoryGraphResultVO;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeProjectRetrievalService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int DEFAULT_GRAPH_BUDGET_MILLIS = 300;
    private static final int MAX_TIMEOUT_MILLIS = 60_000;
    private static final int VECTOR_CANDIDATE_MULTIPLIER = 2;
    private static final int MAX_CHAPTER_COVERAGE = MAX_LIMIT;
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("structured", "fulltext", "vector", "graph");
    private static final Set<String> SUPPORTED_FILTERS = Set.of("chapterFrom", "chapterTo");
    private static final Set<String> SUPPORTED_RERANK_POLICIES = Set.of("intent_aware", "raw_score", "none");
    private static final Pattern GRAPH_TERM = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{1,39}|[\\p{IsHan}]{1,4}");

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectWorkService workService;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final KnowledgeStoryGraphService storyGraphService;

    public KnowledgeProjectRetrievalService(JdbcTemplate jdbcTemplate,
                                            KnowledgeProjectWorkService workService,
                                            EmbeddingClient embeddingClient,
                                            QdrantClient qdrantClient,
                                            KnowledgeStoryGraphService storyGraphService) {
        this.jdbcTemplate = jdbcTemplate;
        this.workService = workService;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.storyGraphService = storyGraphService;
    }

    public ProjectRetrievalResultVO retrieve(ProjectRetrievalRequest request) {
        QueryScope scope = scope(request);
        Deadline deadline = Deadline.start(scope.timeoutMillis());
        workService.findOwnedWorkPublic(scope.projectId(), scope.workId(), scope.userId());
        LinkedHashSet<String> gaps = new LinkedHashSet<>();
        List<Map<String, Object>> candidates = new ArrayList<>();

        if (chapterCoverageRequested(scope) && scope.channels().contains("structured") && withinDeadline(deadline, gaps)) {
            try {
                candidates.addAll(searchChapterRepresentatives(scope));
            } catch (DataAccessException ex) {
                gaps.add("chapter_coverage_unavailable");
            }
        }
        if (scope.channels().contains("structured") && withinDeadline(deadline, gaps)) {
            try {
                candidates.addAll(searchStructured(scope));
            } catch (DataAccessException ex) {
                gaps.add("structured_unavailable");
            }
        }
        if (scope.channels().contains("fulltext") && withinDeadline(deadline, gaps)) {
            try {
                candidates.addAll(searchLexical(scope, gaps));
            } catch (DataAccessException ex) {
                gaps.add("fulltext_unavailable");
            }
        }
        if (scope.channels().contains("vector") && withinDeadline(deadline, gaps)) {
            candidates.addAll(searchDense(scope, gaps));
        }
        if (scope.channels().contains("graph") && withinDeadline(deadline, gaps)) {
            candidates.addAll(searchGraph(scope, request, deadline, gaps));
        }
        withinDeadline(deadline, gaps);

        List<Map<String, Object>> fused = fuse(scope, candidates);
        ChapterCoverage coverage = chapterCoverage(scope, fused);
        if (coverage != null && coverage.missingChapterCount() > 0) {
            gaps.add("chapter_coverage_incomplete");
        }
        ProjectRetrievalResultVO result = new ProjectRetrievalResultVO();
        result.setEvidence(fused);
        result.setGaps(new ArrayList<>(gaps));
        result.setPartial(!gaps.isEmpty());
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("intent", scope.intent());
        diagnostics.put("candidateCount", candidates.size());
        diagnostics.put("returnedCount", fused.size());
        diagnostics.put("channels", backendCounts(candidates));
        diagnostics.put("candidateChannels", channelCounts(candidates));
        diagnostics.put("returnedChannels", channelCounts(fused));
        diagnostics.put("channelStatus", channelStatus(scope, candidates, fused, gaps));
        diagnostics.put("requestedChannels", new ArrayList<>(scope.channels()));
        diagnostics.put("weights", scope.weights());
        diagnostics.put("rerankPolicy", scope.rerankPolicy());
        diagnostics.put("fusionPolicy", "intent_aware".equals(scope.rerankPolicy())
            ? "weighted_channel_rank_diversity"
            : "raw_score");
        diagnostics.put("vectorQueryAugmented", scope.vectorQueryAugmented());
        diagnostics.put("graphBudgetMillis", scope.graphBudgetMillis());
        diagnostics.put("timeoutMillis", scope.timeoutMillis());
        if (coverage != null) {
            diagnostics.put("coveragePolicy", "chapter_balanced");
            diagnostics.put("requestedChapterCount", coverage.requestedChapterCount());
            diagnostics.put("coveredChapters", coverage.coveredChapters());
            diagnostics.put("missingChapters", coverage.missingChapters());
            diagnostics.put("missingChapterCount", coverage.missingChapterCount());
            diagnostics.put("missingChaptersTruncated", coverage.missingChaptersTruncated());
        }
        diagnostics.put("generationScopeCount", deadline.expired() ? 0 : activeChapterGenerationIds(scope).size());
        diagnostics.put("documentGenerationScopeCount", deadline.expired() ? 0 : activeDocumentGenerationIds(scope).size());
        result.setDiagnostics(diagnostics);
        return result;
    }

    private List<Map<String, Object>> searchChapterRepresentatives(QueryScope scope) {
        int coverageLimit = Math.min(scope.limit(), MAX_CHAPTER_COVERAGE);
        return jdbcTemplate.query(
            """
                select c.chapter_id, h.chapter_no, g.generation_id, g.chapter_version,
                    c.title, c.content, c.content_hash
                from ai_project_chapter_head h
                join ai_project_ingest_generation g on g.generation_id = h.active_generation_id
                    and g.status = 'ACTIVE'
                join ai_project_chapter c on c.chapter_id = h.active_chapter_id
                    and c.user_id = h.user_id and c.project_id = h.project_id
                    and c.work_id = h.work_id and c.status = 'ACTIVE'
                where h.user_id = ? and h.project_id = ? and h.work_id = ?
                  and h.chapter_no >= ? and h.chapter_no <= ? and h.tombstoned_at is null
                order by h.chapter_no asc
                limit ?
                """,
            (rs, rowNum) -> map(
                "source", "project_chapter", "backend", "structured", "channel", "structured",
                "sourceId", rs.getLong("chapter_id"), "sourceType", "CHAPTER",
                "chapterId", rs.getLong("chapter_id"), "chapterNo", rs.getInt("chapter_no"),
                "generationId", rs.getLong("generation_id"),
                "chapterVersion", rs.getInt("chapter_version"),
                "title", rs.getString("title"), "preview", abbreviate(rs.getString("content"), 1200),
                "contentHash", rs.getString("content_hash"), "confidence", 1.0d,
                "rawScore", 1.0d, "coverageRepresentative", true
            ),
            scope.userId(), scope.projectId(), scope.workId(),
            scope.chapterFrom(), scope.chapterTo(), coverageLimit
        );
    }

    private List<Map<String, Object>> searchStructured(QueryScope scope) {
        String query = scope.query();
        boolean shortAlias = query.length() <= 2;
        List<Object> args = documentScopeArgs(scope);
        String predicate;
        if (shortAlias) {
            predicate = "d.title = ? or d.aliases = ? or d.aliases like ?";
            args.add(query);
            args.add("|" + query + "|");
            args.add("|" + query + "%");
        } else {
            predicate = "d.title = ? or d.aliases like ? or d.title like ?";
            args.add(query);
            args.add("|" + query + "%");
            args.add(query + "%");
        }
        args.add(scope.limit());
        List<Map<String, Object>> rows = jdbcTemplate.query(
            visibleDocumentSql(scope, predicate, "order by d.document_id asc limit ?"),
            (rs, rowNum) -> documentRow(rs, shortAlias ? 0.98d : 0.90d, "structured"),
            args.toArray()
        );
        return rows;
    }

    private List<Map<String, Object>> searchLexical(QueryScope scope, Set<String> gaps) {
        if (isMysql()) {
            try {
                List<Object> args = documentScopeArgs(scope);
                args.add(scope.query());
                args.add(scope.limit());
                return jdbcTemplate.query(
                    visibleDocumentSql(
                        scope,
                        "match(d.title, d.aliases, d.content) against(? in natural language mode)",
                        "order by d.document_id asc limit ?"
                    ),
                    (rs, rowNum) -> documentRow(rs, 0.78d, "fulltext"),
                    args.toArray()
                );
            } catch (DataAccessException ex) {
                gaps.add("fulltext_unavailable");
            }
        } else {
            gaps.add("fulltext_unavailable");
        }
        return List.of();
    }

    private List<Map<String, Object>> searchDense(QueryScope scope, Set<String> gaps) {
        if (embeddingClient == null || qdrantClient == null) {
            gaps.add("vector_unavailable");
            return List.of();
        }
        List<Long> chapterGenerationIds = activeChapterGenerationIds(scope);
        List<Long> documentGenerationIds = activeDocumentGenerationIds(scope);
        if (chapterGenerationIds.isEmpty() && documentGenerationIds.isEmpty()) {
            return List.of();
        }
        try {
            List<Double> vector = embeddingClient.embed(scope.semanticQuery());
            qdrantClient.ensureCollection();
            int candidateLimit = Math.min(
                MAX_LIMIT * VECTOR_CANDIDATE_MULTIPLIER,
                scope.limit() * VECTOR_CANDIDATE_MULTIPLIER
            );
            List<QdrantClient.SearchResult> results = new ArrayList<>();
            Map<String, Object> filters = Map.of(
                "user_id", scope.userId(),
                "project_id", scope.projectId(),
                "work_id", scope.workId(),
                "visibility", "private"
            );
            if (!chapterGenerationIds.isEmpty()) {
                results.addAll(qdrantClient.searchWithAnyMatch(
                    vector, filters, "generation_id", chapterGenerationIds, candidateLimit
                ));
            }
            if (!documentGenerationIds.isEmpty()) {
                results.addAll(qdrantClient.searchWithAnyMatch(
                    vector, filters, "document_generation_id", documentGenerationIds, candidateLimit
                ));
            }
            List<Map<String, Object>> evidence = new ArrayList<>();
            boolean rejected = false;
            for (QdrantClient.SearchResult result : results) {
                if (!payloadMatchesScope(result.payload(), scope, chapterGenerationIds, documentGenerationIds)) {
                    rejected = true;
                    continue;
                }
                Map<String, Object> row = findVisibleVectorChunk(scope, result);
                if (row == null) {
                    rejected = true;
                    continue;
                }
                evidence.add(row);
            }
            if (rejected) {
                gaps.add("vector_scope_rejected");
            }
            return evidence;
        } catch (RuntimeException ex) {
            gaps.add("vector_unavailable");
            return List.of();
        }
    }

    private Map<String, Object> findVisibleVectorChunk(QueryScope scope, QdrantClient.SearchResult result) {
        Long chunkId = longPayload(result.payload(), "project_vector_chunk_id");
        Long payloadGeneration = longPayload(result.payload(), "generation_id");
        Long payloadVersion = longPayload(result.payload(), "chapter_version");
        Long payloadDocumentGeneration = longPayload(result.payload(), "document_generation_id");
        Long payloadSection = longPayload(result.payload(), "section_id");
        if (chunkId == null) {
            return null;
        }
        if (payloadDocumentGeneration != null || payloadSection != null) {
            if (payloadDocumentGeneration == null || payloadSection == null) {
                return null;
            }
            List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                    select v.id, v.project_id, v.work_id, v.chapter_id, v.generation_id, v.chapter_version,
                        v.scene_id, v.source_type, v.source_id, v.content_hash, v.chunk_text,
                        null chapter_no, v.document_id source_document_id,
                        v.document_generation_id, v.section_id
                    from ai_project_vector_chunk v
                    join ai_project_document d on d.document_id = v.document_id
                        and d.active_generation_id = v.document_generation_id and d.status = 'ACTIVE'
                    join ai_project_document_generation g on g.document_generation_id = v.document_generation_id
                        and g.status = 'ACTIVE'
                    join ai_project_document_section s on s.section_id = v.section_id
                        and s.document_generation_id = v.document_generation_id and s.status = 'ACTIVE'
                    where v.id = ? and v.user_id = ? and v.project_id = ? and v.work_id = ?
                      and v.visibility = 'private' and v.status = 'ACTIVE'
                    limit 1
                    """,
                (rs, rowNum) -> vectorRow(rs, result.score()),
                chunkId, scope.userId(), scope.projectId(), scope.workId()
            );
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            return payloadDocumentGeneration.equals(longValue(row.get("documentGenerationId")))
                && payloadSection.equals(longValue(row.get("sectionId"))) ? row : null;
        }
        if (payloadGeneration == null || payloadVersion == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
                select v.id, v.project_id, v.work_id, v.chapter_id, v.generation_id, v.chapter_version,
                    v.scene_id, v.source_type, v.source_id, v.content_hash, v.chunk_text, h.chapter_no,
                    null source_document_id, null document_generation_id, null section_id
                from ai_project_vector_chunk v
                join ai_project_chapter_head h on h.user_id = v.user_id and h.project_id = v.project_id
                    and h.work_id = v.work_id and h.active_chapter_id = v.chapter_id
                    and h.active_generation_id = v.generation_id and h.tombstoned_at is null
                join ai_project_ingest_generation g on g.generation_id = v.generation_id and g.status = 'ACTIVE'
                where v.id = ? and v.user_id = ? and v.project_id = ? and v.work_id = ?
                  and v.visibility = 'private' and v.status = 'ACTIVE'
                limit 1
                """,
            (rs, rowNum) -> vectorRow(rs, result.score()),
            chunkId, scope.userId(), scope.projectId(), scope.workId()
        );
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (!payloadGeneration.equals(longValue(row.get("generationId"))) || !payloadVersion.equals(longValue(row.get("chapterVersion")))) {
            return null;
        }
        return row;
    }

    private List<Map<String, Object>> searchGraph(QueryScope scope,
                                                   ProjectRetrievalRequest request,
                                                   Deadline deadline,
                                                   Set<String> gaps) {
        if (storyGraphService == null) {
            gaps.add("graph_unavailable");
            return List.of();
        }
        try {
            int graphBudgetMillis = deadline.remainingMillis(scope.graphBudgetMillis());
            if (graphBudgetMillis <= 0) {
                gaps.add("retrieval_timeout");
                return List.of();
            }
            StoryGraphResultVO graph = storyGraphService.traverse(
                scope.userId(), scope.projectId(), scope.workId(), graphTerms(request, scope.query()),
                Boolean.TRUE.equals(request.getDeep()), graphBudgetMillis
            );
            gaps.addAll(graph.getGaps());
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Map<String, Object> edge : graph.getEdges()) {
                Long evidenceChapterId = longValue(edge.get("evidenceChapterId"));
                Long generationId = longValue(edge.get("generationId"));
                if (evidenceChapterId == null || generationId == null) {
                    continue;
                }
                Map<String, Object> chapter = activeChapterMeta(scope, evidenceChapterId, generationId);
                if (chapter == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", "story_graph");
                item.put("backend", "graph");
                item.put("channel", "graph");
                item.put("sourceId", edge.get("edgeId"));
                item.put("chapterId", evidenceChapterId);
                item.put("chapterNo", chapter.get("chapterNo"));
                item.put("generationId", generationId);
                item.put("chapterVersion", chapter.get("chapterVersion"));
                item.put("contentHash", chapter.get("contentHash"));
                item.put("title", edge.get("relationType"));
                item.put("preview", edge.get("evidenceRef"));
                item.put("confidence", edge.get("confidence"));
                item.put("rawScore", normalizeScore(doubleValue(edge.get("confidence")), 0.80d));
                item.put("edge", edge);
                evidence.add(item);
            }
            return evidence;
        } catch (RuntimeException ex) {
            gaps.add("graph_unavailable");
            return List.of();
        }
    }

    private Map<String, Object> activeChapterMeta(QueryScope scope, Long chapterId, Long generationId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
                select h.chapter_no, g.chapter_version, c.content_hash
                from ai_project_chapter_head h
                join ai_project_ingest_generation g on g.generation_id = h.active_generation_id and g.status = 'ACTIVE'
                join ai_project_chapter c on c.chapter_id = h.active_chapter_id
                where h.user_id = ? and h.project_id = ? and h.work_id = ? and h.active_chapter_id = ?
                  and h.active_generation_id = ? and h.tombstoned_at is null
                limit 1
                """,
            (rs, rowNum) -> map("chapterNo", rs.getInt("chapter_no"), "chapterVersion", rs.getInt("chapter_version"), "contentHash", rs.getString("content_hash")),
            scope.userId(), scope.projectId(), scope.workId(), chapterId, generationId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> fuse(QueryScope scope, List<Map<String, Object>> candidates) {
        Map<String, List<Map<String, Object>>> candidatesByChannel = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String channel = channelName(String.valueOf(candidate.get("backend")));
            candidate.put("channel", channel);
            candidatesByChannel.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<Map<String, Object>> channelCandidates : candidatesByChannel.values()) {
            channelCandidates.sort(Comparator
                .comparing((Map<String, Object> item) -> doubleValue(item.get("rawScore")), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(this::evidenceIdentity));
            int channelSize = channelCandidates.size();
            for (int index = 0; index < channelSize; index++) {
                Map<String, Object> candidate = channelCandidates.get(index);
                String backend = String.valueOf(candidate.get("backend"));
                double rawScore = normalizeScore(doubleValue(candidate.get("rawScore")), 0.0d);
                double rankScore = 1.0d - ((double) index / Math.max(1, channelSize));
                double fusedScore = "intent_aware".equals(scope.rerankPolicy())
                    ? channelWeight(scope, backend) * ((rawScore * 0.75d) + (rankScore * 0.25d))
                    : rawScore;
                candidate.put("channelRank", index + 1);
                candidate.put("score", fusedScore);
            }
        }
        Map<String, Map<String, Object>> bestBySource = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String identity = evidenceIdentity(candidate);
            Map<String, Object> existing = bestBySource.get(identity);
            if (existing == null || doubleValue(candidate.get("score")) > doubleValue(existing.get("score"))) {
                bestBySource.put(identity, candidate);
            }
        }
        List<Map<String, Object>> ranked = bestBySource.values().stream()
            .sorted(Comparator
                .comparing((Map<String, Object> item) -> doubleValue(item.get("score")), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> nullableInt(item.get("chapterNo")), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> String.valueOf(item.get("backend")))
                .thenComparing(item -> String.valueOf(item.get("sourceId"))))
            .toList();
        List<Map<String, Object>> selected = new ArrayList<>();
        Set<String> selectedIdentities = new LinkedHashSet<>();
        Set<String> representedChannels = new LinkedHashSet<>();
        if (chapterCoverageRequested(scope)) {
            List<Map<String, Object>> chapterRepresentatives = ranked.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.get("coverageRepresentative")))
                .sorted(Comparator.comparing(
                    item -> nullableInt(item.get("chapterNo")),
                    Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
            for (Map<String, Object> candidate : chapterRepresentatives) {
                if (Boolean.TRUE.equals(candidate.get("coverageRepresentative"))
                    && selectedIdentities.add(evidenceIdentity(candidate))) {
                    selected.add(candidate);
                    representedChannels.add(String.valueOf(candidate.get("channel")));
                    if (selected.size() >= scope.limit()) {
                        return selected;
                    }
                }
            }
        }
        if (!"intent_aware".equals(scope.rerankPolicy()) || scope.limit() <= 1) {
            for (Map<String, Object> candidate : ranked) {
                if (selectedIdentities.add(evidenceIdentity(candidate))) {
                    selected.add(candidate);
                    if (selected.size() >= scope.limit()) {
                        break;
                    }
                }
            }
            return selected;
        }
        for (Map<String, Object> candidate : ranked) {
            String channel = String.valueOf(candidate.get("channel"));
            if (representedChannels.add(channel)) {
                selected.add(candidate);
                selectedIdentities.add(evidenceIdentity(candidate));
                if (selected.size() >= scope.limit()) {
                    return selected;
                }
            }
        }
        for (Map<String, Object> candidate : ranked) {
            if (selectedIdentities.add(evidenceIdentity(candidate))) {
                selected.add(candidate);
                if (selected.size() >= scope.limit()) {
                    break;
                }
            }
        }
        return selected;
    }

    private boolean chapterCoverageRequested(QueryScope scope) {
        return scope.chapterFrom() != null
            && scope.chapterTo() != null
            && scope.chapterTo() > scope.chapterFrom();
    }

    private ChapterCoverage chapterCoverage(QueryScope scope, List<Map<String, Object>> evidence) {
        if (!chapterCoverageRequested(scope)) {
            return null;
        }
        LinkedHashSet<Integer> covered = new LinkedHashSet<>();
        evidence.stream()
            .map(item -> nullableInt(item.get("chapterNo")))
            .filter(chapterNo -> chapterNo != null
                && chapterNo >= scope.chapterFrom()
                && chapterNo <= scope.chapterTo())
            .sorted()
            .forEach(covered::add);
        long requestedCount = (long) scope.chapterTo() - scope.chapterFrom() + 1L;
        long missingCount = Math.max(0L, requestedCount - covered.size());
        List<Integer> missing = new ArrayList<>();
        for (long chapterNo = scope.chapterFrom();
             chapterNo <= scope.chapterTo() && missing.size() < MAX_CHAPTER_COVERAGE;
             chapterNo++) {
            int normalized = (int) chapterNo;
            if (!covered.contains(normalized)) {
                missing.add(normalized);
            }
        }
        return new ChapterCoverage(
            requestedCount,
            new ArrayList<>(covered),
            missing,
            missingCount,
            missingCount > missing.size()
        );
    }

    private double channelWeight(QueryScope scope, String backend) {
        if (!"intent_aware".equals(scope.rerankPolicy())) {
            return 1.0d;
        }
        String channel = "qdrant".equals(backend) ? "vector" : backend;
        Double configured = scope.weights().get(channel);
        if (configured != null) {
            return configured;
        }
        String normalizedIntent = scope.intent() == null ? "" : scope.intent().toLowerCase(Locale.ROOT);
        if (normalizedIntent.contains("foreshadow") || normalizedIntent.contains("continuity") || normalizedIntent.contains("timeline")) {
            return switch (backend) {
                case "graph" -> 1.00d;
                case "structured" -> 0.90d;
                case "fulltext" -> 0.75d;
                case "qdrant" -> 0.70d;
                default -> 0.60d;
            };
        }
        if (normalizedIntent.contains("chapter") || normalizedIntent.contains("recall")) {
            return switch (backend) {
                case "structured" -> 1.00d;
                case "qdrant" -> 0.88d;
                case "fulltext" -> 0.82d;
                case "graph" -> 0.70d;
                default -> 0.60d;
            };
        }
        return switch (backend) {
            case "structured" -> 0.95d;
            case "graph" -> 0.90d;
            case "qdrant" -> 0.85d;
            case "fulltext" -> 0.80d;
            default -> 0.60d;
        };
    }

    private String visibleDocumentSql(QueryScope scope, String predicate, String order) {
        return """
            select d.document_id, d.document_type, d.source_id, d.scene_id, d.chapter_id, d.generation_id,
                d.chapter_version, d.title, d.content, d.content_hash, d.confidence, h.chapter_no,
                d.source_document_id, d.document_generation_id, d.section_id
            from ai_project_search_document d
            left join ai_project_chapter_head h on h.user_id = d.user_id and h.project_id = d.project_id
                and h.work_id = d.work_id and h.active_chapter_id = d.chapter_id
                and h.active_generation_id = d.generation_id and h.tombstoned_at is null
            left join ai_project_ingest_generation g on g.generation_id = d.generation_id and g.status = 'ACTIVE'
            left join ai_project_document pd on pd.document_id = d.source_document_id
                and pd.active_generation_id = d.document_generation_id and pd.status = 'ACTIVE'
            left join ai_project_document_generation dg on dg.document_generation_id = d.document_generation_id
                and dg.status = 'ACTIVE'
            left join ai_project_document_section ds on ds.section_id = d.section_id
                and ds.document_generation_id = d.document_generation_id and ds.status = 'ACTIVE'
            where d.user_id = ? and d.project_id = ? and d.work_id = ? and d.status = 'ACTIVE'
              and ((d.generation_id is not null and h.active_generation_id is not null and g.generation_id is not null)
                or (d.document_generation_id is not null and pd.active_generation_id is not null
                    and dg.document_generation_id is not null and ds.section_id is not null))
            """ + chapterRangeSql(scope) + " and (" + predicate + ") " + order;
    }

    private List<Object> documentScopeArgs(QueryScope scope) {
        List<Object> args = new ArrayList<>(List.of(scope.userId(), scope.projectId(), scope.workId()));
        if (scope.chapterFrom() != null) {
            args.add(scope.chapterFrom());
        }
        if (scope.chapterTo() != null) {
            args.add(scope.chapterTo());
        }
        return args;
    }

    private String chapterRangeSql(QueryScope scope) {
        StringBuilder sql = new StringBuilder();
        if (scope.chapterFrom() != null) {
            sql.append(" and (d.document_generation_id is not null or h.chapter_no >= ?)");
        }
        if (scope.chapterTo() != null) {
            sql.append(" and (d.document_generation_id is not null or h.chapter_no <= ?)");
        }
        return sql.toString();
    }

    private List<Long> activeChapterGenerationIds(QueryScope scope) {
        List<Object> args = new ArrayList<>(List.of(scope.userId(), scope.projectId(), scope.workId()));
        if (scope.chapterFrom() != null) {
            args.add(scope.chapterFrom());
        }
        if (scope.chapterTo() != null) {
            args.add(scope.chapterTo());
        }
        return jdbcTemplate.query(
            """
                select distinct h.active_generation_id
                from ai_project_chapter_head h
                join ai_project_ingest_generation g on g.generation_id = h.active_generation_id and g.status = 'ACTIVE'
                where h.user_id = ? and h.project_id = ? and h.work_id = ? and h.tombstoned_at is null
            """ + activeChapterRangeSql(scope) + " order by h.active_generation_id asc",
            (rs, rowNum) -> rs.getLong(1), args.toArray()
        );
    }

    private List<Long> activeDocumentGenerationIds(QueryScope scope) {
        return jdbcTemplate.query(
            """
                select distinct d.active_generation_id
                from ai_project_document d
                join ai_project_document_generation g on g.document_generation_id = d.active_generation_id
                    and g.status = 'ACTIVE'
                where d.user_id = ? and d.project_id = ? and d.work_id = ? and d.status = 'ACTIVE'
                  and d.active_generation_id is not null
                order by d.active_generation_id asc
                """,
            (rs, rowNum) -> rs.getLong(1), scope.userId(), scope.projectId(), scope.workId()
        );
    }

    private String activeChapterRangeSql(QueryScope scope) {
        StringBuilder sql = new StringBuilder();
        if (scope.chapterFrom() != null) {
            sql.append(" and h.chapter_no >= ?");
        }
        if (scope.chapterTo() != null) {
            sql.append(" and h.chapter_no <= ?");
        }
        return sql.toString();
    }

    private QueryScope scope(ProjectRetrievalRequest request) {
        if (request == null || request.getUserId() == null || request.getProjectId() == null || request.getWorkId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project retrieval scope is required");
        }
        String query = trim(request.getQuery(), 500);
        if (query == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project retrieval query is required");
        }
        Map<String, Object> filters = normalizeFilters(request.getFilters());
        Integer chapterFrom = mergeChapterBound("chapterFrom", request.getChapterFrom(), filters.get("chapterFrom"));
        Integer chapterTo = mergeChapterBound("chapterTo", request.getChapterTo(), filters.get("chapterTo"));
        if (chapterFrom != null && chapterTo != null && chapterFrom > chapterTo) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter range is invalid");
        }
        return new QueryScope(
            request.getUserId(), request.getProjectId(), request.getWorkId(), query,
            semanticQuery(query, request.getEntities()), hasSemanticQueryExpansion(request.getEntities()),
            trim(request.getIntent(), 80) == null ? "project_knowledge_qa" : trim(request.getIntent(), 80),
            chapterFrom, chapterTo, normalizeChannels(request.getChannels()), normalizeWeights(request.getWeights()),
            normalizeLimit(request.getLimit()), normalizeGraphBudget(request.getGraphBudgetMillis()),
            normalizeTimeout(request.getTimeoutMillis()), normalizeRerankPolicy(request.getRerankPolicy())
        );
    }

    private boolean withinDeadline(Deadline deadline, Set<String> gaps) {
        if (!deadline.expired()) {
            return true;
        }
        gaps.add("retrieval_timeout");
        return false;
    }

    private Set<String> normalizeChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return new LinkedHashSet<>(List.of("structured", "fulltext", "vector", "graph"));
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : channels) {
            String channel = trim(value, 40);
            channel = channel == null ? null : channel.toLowerCase(Locale.ROOT);
            if (channel == null || !SUPPORTED_CHANNELS.contains(channel)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported project retrieval channel");
            }
            normalized.add(channel);
        }
        return normalized;
    }

    private Map<String, Object> normalizeFilters(Map<String, Object> filters) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filters == null ? Map.<String, Object>of().entrySet() : filters.entrySet()) {
            if (!SUPPORTED_FILTERS.contains(entry.getKey())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported project retrieval filter");
            }
            normalized.put(entry.getKey(), entry.getValue());
        }
        return normalized;
    }

    private Integer mergeChapterBound(String key, Integer explicit, Object filtered) {
        Integer explicitValue = positiveOrNull(explicit);
        Integer filteredValue = positiveOrNull(integerValue(filtered));
        if (explicitValue != null && filteredValue != null && !explicitValue.equals(filteredValue)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, key + " conflicts with retrieval filters");
        }
        return explicitValue == null ? filteredValue : explicitValue;
    }

    private Map<String, Double> normalizeWeights(Map<String, Double> weights) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : weights == null ? Map.<String, Double>of().entrySet() : weights.entrySet()) {
            String channel = trim(entry.getKey(), 40);
            channel = channel == null ? null : channel.toLowerCase(Locale.ROOT);
            Double weight = entry.getValue();
            if (channel == null || !SUPPORTED_CHANNELS.contains(channel) || weight == null
                || weight.isNaN() || weight.isInfinite() || weight < 0.0d || weight > 1.0d) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "invalid project retrieval weight");
            }
            normalized.put(channel, weight);
        }
        return normalized;
    }

    private int normalizeGraphBudget(Integer value) {
        return value == null ? DEFAULT_GRAPH_BUDGET_MILLIS : Math.max(1, Math.min(DEFAULT_GRAPH_BUDGET_MILLIS, value));
    }

    private Integer normalizeTimeout(Integer value) {
        return value == null ? null : Math.max(1, Math.min(MAX_TIMEOUT_MILLIS, value));
    }

    private String normalizeRerankPolicy(String value) {
        String normalized = trim(value, 40);
        normalized = normalized == null ? "intent_aware" : normalized.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_RERANK_POLICIES.contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported project retrieval rerank policy");
        }
        return normalized;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter bounds must be integers");
        }
    }

    private boolean payloadMatchesScope(Map<String, Object> payload,
                                        QueryScope scope,
                                        Collection<Long> chapterGenerationIds,
                                        Collection<Long> documentGenerationIds) {
        if (payload == null) {
            return false;
        }
        Long userId = longPayload(payload, "user_id");
        Long projectId = longPayload(payload, "project_id");
        Long workId = longPayload(payload, "work_id");
        Long generationId = longPayload(payload, "generation_id");
        Long documentGenerationId = longPayload(payload, "document_generation_id");
        Long sectionId = longPayload(payload, "section_id");
        Long chapterId = longPayload(payload, "chapter_id");
        Long chapterVersion = longPayload(payload, "chapter_version");
        return scope.userId().equals(userId)
            && scope.projectId().equals(projectId)
            && scope.workId().equals(workId)
            && ((chapterId != null && chapterVersion != null && generationId != null
                && chapterGenerationIds.contains(generationId))
                || (documentGenerationId != null && sectionId != null
                    && documentGenerationIds.contains(documentGenerationId)))
            && "private".equals(payload.get("visibility"));
    }

    private Map<String, Object> documentRow(ResultSet rs, double rawScore, String backend) throws SQLException {
        return map(
            "source", "project_document", "backend", backend, "channel", channelName(backend),
            "documentId", rs.getLong("document_id"),
            "sourceId", nullableLong(rs, "source_id"), "sourceType", rs.getString("document_type"),
            "chapterId", rs.getLong("chapter_id"), "chapterNo", rs.getInt("chapter_no"),
            "generationId", rs.getLong("generation_id"), "chapterVersion", rs.getInt("chapter_version"),
            "sourceDocumentId", nullableLong(rs, "source_document_id"),
            "documentGenerationId", nullableLong(rs, "document_generation_id"),
            "sectionId", nullableLong(rs, "section_id"),
            "sceneId", nullableLong(rs, "scene_id"), "title", rs.getString("title"),
            "preview", abbreviate(rs.getString("content"), 1200), "contentHash", rs.getString("content_hash"),
            "confidence", decimal(rs, "confidence"), "rawScore", rawScore
        );
    }

    private Map<String, Object> vectorRow(ResultSet rs, double score) throws SQLException {
        return map(
            "source", "project_vector_chunk", "backend", "qdrant", "channel", "vector",
            "chunkId", rs.getLong("id"),
            "sourceId", nullableLong(rs, "source_id"), "sourceType", rs.getString("source_type"),
            "chapterId", nullableLong(rs, "chapter_id"), "chapterNo", rs.getInt("chapter_no"),
            "generationId", nullableLong(rs, "generation_id"), "chapterVersion", nullableInt(rs, "chapter_version"),
            "sourceDocumentId", nullableLong(rs, "source_document_id"),
            "documentGenerationId", nullableLong(rs, "document_generation_id"),
            "sectionId", nullableLong(rs, "section_id"),
            "sceneId", nullableLong(rs, "scene_id"), "preview", abbreviate(rs.getString("chunk_text"), 1200),
            "contentHash", rs.getString("content_hash"), "rawScore", normalizeScore(score, 0.0d)
        );
    }

    private List<String> graphTerms(ProjectRetrievalRequest request, String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (request.getEntities() != null) {
            for (String entity : request.getEntities()) {
                String normalized = trim(entity, 120);
                if (normalized != null) {
                    terms.add(normalized);
                }
            }
        }
        Matcher matcher = GRAPH_TERM.matcher(query);
        while (matcher.find() && terms.size() < 8) {
            terms.add(matcher.group());
        }
        return new ArrayList<>(terms);
    }

    private Map<String, Integer> backendCounts(List<Map<String, Object>> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String backend = String.valueOf(candidate.get("backend"));
            counts.merge(backend, 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, Integer> channelCounts(List<Map<String, Object>> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            String channel = channelName(String.valueOf(candidate.get("backend")));
            counts.merge(channel, 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, String> channelStatus(QueryScope scope,
                                               List<Map<String, Object>> candidates,
                                               List<Map<String, Object>> fused,
                                               Set<String> gaps) {
        Map<String, Integer> candidateCounts = channelCounts(candidates);
        Map<String, Integer> returnedCounts = channelCounts(fused);
        Map<String, String> status = new LinkedHashMap<>();
        for (String channel : scope.channels()) {
            if (returnedCounts.getOrDefault(channel, 0) > 0) {
                status.put(channel, "used");
            } else if (gaps.contains(channel + "_unavailable")) {
                status.put(channel, "unavailable");
            } else if ("vector".equals(channel) && gaps.contains("vector_scope_rejected")) {
                status.put(channel, "scope_rejected");
            } else if (candidateCounts.getOrDefault(channel, 0) > 0) {
                status.put(channel, "retrieved_not_selected");
            } else {
                status.put(channel, "no_match");
            }
        }
        return status;
    }

    private String channelName(String backend) {
        if ("qdrant".equals(backend)) {
            return "vector";
        }
        if ("lexical".equals(backend)) {
            return "fulltext";
        }
        return backend;
    }

    private String evidenceIdentity(Map<String, Object> candidate) {
        if (candidate.get("documentId") != null) {
            return "document:" + candidate.get("documentId");
        }
        if (candidate.get("chunkId") != null) {
            return "chunk:" + candidate.get("chunkId");
        }
        if (candidate.get("sourceId") != null && "graph".equals(candidate.get("backend"))) {
            return "edge:" + candidate.get("sourceId");
        }
        return String.valueOf(candidate.get("chapterId")) + ":" + candidate.get("sceneId") + ":" + candidate.get("sourceType");
    }

    private boolean isMysql() {
        Boolean mysql = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
        });
        return Boolean.TRUE.equals(mysql);
    }

    private int normalizeLimit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limit));
    }

    private String semanticQuery(String query, List<String> entities) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        parts.add(query);
        if (entities != null) {
            for (String entity : entities) {
                String normalized = trim(entity, 120);
                if (normalized != null) {
                    parts.add(normalized);
                }
                if (parts.size() >= 6) {
                    break;
                }
            }
        }
        return String.join("\n", parts);
    }

    private boolean hasSemanticQueryExpansion(List<String> entities) {
        if (entities == null) {
            return false;
        }
        return entities.stream().anyMatch(entity -> trim(entity, 120) != null);
    }

    private Integer positiveOrNull(Integer value) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter bounds must be positive");
        }
        return value;
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private Long longPayload(Map<String, Object> payload, String key) {
        return longValue(payload == null ? null : payload.get(key));
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer nullableInt(Object value) {
        Long numeric = longValue(value);
        return numeric == null ? null : numeric.intValue();
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.longValue() : null;
    }

    private Double decimal(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private double normalizeScore(Double value, double fallback) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return fallback;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private record QueryScope(Long userId, Long projectId, Long workId, String query,
                              String semanticQuery, boolean vectorQueryAugmented, String intent,
                              Integer chapterFrom, Integer chapterTo, Set<String> channels,
                              Map<String, Double> weights, int limit, int graphBudgetMillis,
                              Integer timeoutMillis, String rerankPolicy) { }

    private record ChapterCoverage(long requestedChapterCount, List<Integer> coveredChapters,
                                   List<Integer> missingChapters, long missingChapterCount,
                                   boolean missingChaptersTruncated) { }

    private record Deadline(long startedAtNanos, long timeoutNanos) {
        private static Deadline start(Integer timeoutMillis) {
            return new Deadline(
                System.nanoTime(),
                timeoutMillis == null ? Long.MAX_VALUE : TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            );
        }

        private boolean expired() {
            return timeoutNanos != Long.MAX_VALUE && System.nanoTime() - startedAtNanos >= timeoutNanos;
        }

        private int remainingMillis(int maximum) {
            if (timeoutNanos == Long.MAX_VALUE) {
                return maximum;
            }
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedAtNanos);
            if (remainingNanos <= 0) {
                return 0;
            }
            long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            return (int) Math.min(maximum, remainingMillis);
        }
    }
}

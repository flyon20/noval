package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.ProjectChapterImportRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectWorkRequest;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KnowledgeProjectWorkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectWorkService.class);
    private static final int SCENE_TARGET_CHARS = 900;
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\R\\s*\\R+");

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectService projectService;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;

    @Autowired
    public KnowledgeProjectWorkService(JdbcTemplate jdbcTemplate,
                                       KnowledgeProjectService projectService,
                                       EmbeddingClient embeddingClient,
                                       QdrantClient qdrantClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
    }

    public KnowledgeProjectWorkService(JdbcTemplate jdbcTemplate, KnowledgeProjectService projectService) {
        this(jdbcTemplate, projectService, null, null);
    }

    public ProjectWorkVO createWork(Long projectId, ProjectWorkRequest request) {
        AuthUser user = requireUser();
        projectService.ensureOwned(projectId, user.getUserId());
        String title = requireText(request == null ? null : request.getTitle(), "work title is required", 200);
        String alias = trimToNull(request == null ? null : request.getAlias(), 500);
        String genre = trimToNull(request == null ? null : request.getGenre(), 80);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                    insert into ai_project_work(user_id, project_id, title, alias, genre, status)
                    values(?, ?, ?, ?, ?, 'ACTIVE')
                    """,
                new String[]{"work_id"}
            );
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setString(3, title);
            statement.setString(4, alias);
            statement.setString(5, genre);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "work id missing");
        }
        return findOwnedWork(projectId, key.longValue(), user.getUserId());
    }

    public List<ProjectWorkVO> listWorks(Long projectId) {
        AuthUser user = requireUser();
        projectService.ensureOwned(projectId, user.getUserId());
        return jdbcTemplate.query(
            """
                select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                from ai_project_work
                where project_id = ? and user_id = ? and status <> 'ARCHIVED'
                order by updated_at desc, work_id desc
                """,
            workMapper(),
            projectId,
            user.getUserId()
        );
    }

    public ProjectChapterVO importChapter(Long projectId, Long workId, ProjectChapterImportRequest request) {
        AuthUser user = requireUser();
        ProjectWorkVO work = findOwnedWork(projectId, workId, user.getUserId());
        int chapterNo = request == null || request.getChapterNo() == null ? 0 : request.getChapterNo();
        if (chapterNo <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter no is required");
        }
        String content = requireText(request.getContent(), "chapter content is required", Integer.MAX_VALUE);
        String hash = sha256(content);
        List<ProjectChapterVO> existing = jdbcTemplate.query(
            """
                select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                    word_count, source_type, version, status, created_at, updated_at
                from ai_project_chapter
                where work_id = ? and chapter_no = ? and content_hash = ? and status <> 'ARCHIVED'
                order by chapter_id asc
                """,
            chapterMapper(),
            workId,
            chapterNo,
            hash
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        int nextVersion = nextChapterVersion(workId, chapterNo);
        String sourceType = trimToNull(request.getSourceType(), 40);
        if (sourceType == null) {
            sourceType = "upload";
        }
        String title = trimToNull(request.getTitle(), 200);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String finalSourceType = sourceType;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                    insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content,
                        content_hash, word_count, source_type, version, status)
                    values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """,
                new String[]{"chapter_id"}
            );
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setLong(3, work.getWorkId());
            statement.setInt(4, chapterNo);
            statement.setString(5, title);
            statement.setString(6, content);
            statement.setString(7, hash);
            statement.setInt(8, content.length());
            statement.setString(9, finalSourceType);
            statement.setInt(10, nextVersion);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chapter id missing");
        }
        ProjectChapterVO chapter = findOwnedChapter(projectId, workId, key.longValue(), user.getUserId());
        createIngestArtifacts(chapter);
        return chapter;
    }

    public List<ProjectChapterVO> listChapters(Long projectId, Long workId) {
        AuthUser user = requireUser();
        findOwnedWork(projectId, workId, user.getUserId());
        return listChaptersForUser(user.getUserId(), projectId, workId, null, 200);
    }

    public List<ProjectChapterVO> searchChapters(Long userId, Long projectId, Long workId, String query, int limit) {
        findOwnedWork(projectId, workId, userId);
        return listChaptersForUser(userId, projectId, workId, trimToNull(query, 200), limit);
    }

    public Map<String, Object> resolveWork(Long userId, Long projectId, Long workId, String query, int limit) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "user id is required");
        }
        if (projectId != null && workId != null) {
            ProjectWorkVO work = findOwnedWork(projectId, workId, userId);
            return resolvedWork(work);
        }
        String normalizedQuery = trimToNull(query, 200);
        if (normalizedQuery == null) {
            return mapRow("status", "not_found", "candidates", List.of());
        }
        int safeLimit = Math.max(1, Math.min(limit, 20));
        String like = "%" + normalizedQuery + "%";
        List<ProjectWorkVO> works;
        if (projectId == null) {
            works = jdbcTemplate.query(
                """
                    select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                    from ai_project_work
                    where user_id = ? and status <> 'ARCHIVED'
                      and (title = ? or alias = ? or title like ? or alias like ?)
                    order by case when title = ? or alias = ? then 0 else 1 end,
                        updated_at desc, work_id desc
                    limit ?
                    """,
                workMapper(),
                userId,
                normalizedQuery,
                normalizedQuery,
                like,
                like,
                normalizedQuery,
                normalizedQuery,
                safeLimit
            );
        } else {
            projectService.ensureOwned(projectId, userId);
            works = jdbcTemplate.query(
                """
                    select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                    from ai_project_work
                    where user_id = ? and project_id = ? and status <> 'ARCHIVED'
                      and (title = ? or alias = ? or title like ? or alias like ?)
                    order by case when title = ? or alias = ? then 0 else 1 end,
                        updated_at desc, work_id desc
                    limit ?
                    """,
                workMapper(),
                userId,
                projectId,
                normalizedQuery,
                normalizedQuery,
                like,
                like,
                normalizedQuery,
                normalizedQuery,
                safeLimit
            );
        }
        if (works.isEmpty()) {
            return mapRow("status", "not_found", "candidates", List.of());
        }
        List<ProjectWorkVO> exact = works.stream()
            .filter(work -> normalizedQuery.equalsIgnoreCase(work.getTitle())
                || normalizedQuery.equalsIgnoreCase(work.getAlias()))
            .toList();
        if (exact.size() == 1) {
            return resolvedWork(exact.get(0));
        }
        if (works.size() == 1) {
            return resolvedWork(works.get(0));
        }
        return mapRow(
            "status", "ambiguous",
            "candidates", works.stream().map(this::workCandidate).toList()
        );
    }

    public List<Map<String, Object>> listForeshadowings(Long userId,
                                                        Long projectId,
                                                        Long workId,
                                                        String status,
                                                        int limit) {
        findOwnedWork(projectId, workId, userId);
        String normalizedStatus = trimToNull(status, 30);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if (normalizedStatus == null) {
            return jdbcTemplate.query(
                """
                    select foreshadowing_id, project_id, work_id, title, content, status,
                        planted_chapter_no, paid_off_chapter_no, importance, confidence
                    from ai_project_foreshadowing
                    where user_id = ? and project_id = ? and work_id = ?
                    order by planted_chapter_no asc, foreshadowing_id asc
                    limit ?
                    """,
                (rs, rowNum) -> mapRow(
                    "foreshadowingId", rs.getLong("foreshadowing_id"),
                    "projectId", rs.getLong("project_id"),
                    "workId", rs.getLong("work_id"),
                    "title", rs.getString("title"),
                    "content", rs.getString("content"),
                    "status", rs.getString("status"),
                    "plantedChapterNo", rs.getObject("planted_chapter_no"),
                    "paidOffChapterNo", rs.getObject("paid_off_chapter_no"),
                    "importance", rs.getString("importance"),
                    "confidence", rs.getObject("confidence")
                ),
                userId, projectId, workId, safeLimit
            );
        }
        return jdbcTemplate.query(
            """
                select foreshadowing_id, project_id, work_id, title, content, status,
                    planted_chapter_no, paid_off_chapter_no, importance, confidence
                from ai_project_foreshadowing
                where user_id = ? and project_id = ? and work_id = ? and status = ?
                order by planted_chapter_no asc, foreshadowing_id asc
                limit ?
                """,
            (rs, rowNum) -> mapRow(
                "foreshadowingId", rs.getLong("foreshadowing_id"),
                "projectId", rs.getLong("project_id"),
                "workId", rs.getLong("work_id"),
                "title", rs.getString("title"),
                "content", rs.getString("content"),
                "status", rs.getString("status"),
                "plantedChapterNo", rs.getObject("planted_chapter_no"),
                "paidOffChapterNo", rs.getObject("paid_off_chapter_no"),
                "importance", rs.getString("importance"),
                "confidence", rs.getObject("confidence")
            ),
            userId, projectId, workId, normalizedStatus, safeLimit
        );
    }

    public List<Map<String, Object>> lookupTimeline(Long userId, Long projectId, Long workId, String query, int limit) {
        findOwnedWork(projectId, workId, userId);
        return queryStructured(
            """
                select event_id, project_id, work_id, chapter_no, event_order, title, summary, confidence
                from ai_project_timeline_event
                where user_id = ? and project_id = ? and work_id = ?
                  and (? is null or title like ? or summary like ?)
                order by chapter_no asc, event_order asc, event_id asc
                limit ?
                """,
            userId, projectId, workId, query, limit,
            "eventId", "event_id",
            "chapterNo", "chapter_no",
            "eventOrder", "event_order",
            "title", "title",
            "summary", "summary"
        );
    }

    public List<Map<String, Object>> lookupCharacterStates(Long userId, Long projectId, Long workId, String query, int limit) {
        findOwnedWork(projectId, workId, userId);
        return queryStructured(
            """
                select state_id, project_id, work_id, character_name, chapter_no, state_summary, motivation, confidence
                from ai_project_character_state
                where user_id = ? and project_id = ? and work_id = ?
                  and (? is null or character_name like ? or state_summary like ? or motivation like ?)
                order by chapter_no asc, state_id asc
                limit ?
                """,
            userId, projectId, workId, query, limit,
            "stateId", "state_id",
            "characterName", "character_name",
            "chapterNo", "chapter_no",
            "stateSummary", "state_summary",
            "motivation", "motivation"
        );
    }

    public List<Map<String, Object>> lookupWorldRules(Long userId, Long projectId, Long workId, String query, int limit) {
        findOwnedWork(projectId, workId, userId);
        return queryStructured(
            """
                select rule_id, project_id, work_id, rule_type, title, content, first_chapter_no, confidence
                from ai_project_world_rule
                where user_id = ? and project_id = ? and work_id = ?
                  and (? is null or title like ? or content like ? or rule_type like ?)
                order by first_chapter_no asc, rule_id asc
                limit ?
                """,
            userId, projectId, workId, query, limit,
            "ruleId", "rule_id",
            "ruleType", "rule_type",
            "title", "title",
            "content", "content",
            "firstChapterNo", "first_chapter_no"
        );
    }

    public List<Map<String, Object>> searchVectorChunks(Long userId,
                                                        Long projectId,
                                                        Long workId,
                                                        String query,
                                                        int limit) {
        findOwnedWork(projectId, workId, userId);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String normalizedQuery = trimToNull(query, 200);
        if (normalizedQuery == null) {
            List<Map<String, Object>> chunks = jdbcTemplate.query(
                """
                    select id, project_id, work_id, chapter_id, scene_id, source_type, source_id,
                        qdrant_point_id, chunk_text, visibility
                    from ai_project_vector_chunk
                    where user_id = ? and project_id = ? and work_id = ? and visibility = 'private'
                    order by id asc
                    limit ?
                    """,
                (rs, rowNum) -> mapVectorChunk(rs),
                userId, projectId, workId, safeLimit
            );
            return markRetrievalBackend(chunks, "lexical");
        }
        List<Map<String, Object>> qdrantResults = searchVectorChunksByQdrant(
            userId,
            projectId,
            workId,
            normalizedQuery,
            safeLimit
        );
        if (!qdrantResults.isEmpty()) {
            return qdrantResults;
        }
        String like = "%" + normalizedQuery + "%";
        List<Map<String, Object>> chunks = jdbcTemplate.query(
            """
                select id, project_id, work_id, chapter_id, scene_id, source_type, source_id,
                    qdrant_point_id, chunk_text, visibility
                from ai_project_vector_chunk
                where user_id = ? and project_id = ? and work_id = ? and visibility = 'private'
                  and chunk_text like ?
                order by id asc
                limit ?
                """,
            (rs, rowNum) -> mapVectorChunk(rs),
            userId, projectId, workId, like, safeLimit
        );
        return markRetrievalBackend(chunks, "lexical");
    }

    private List<ProjectChapterVO> listChaptersForUser(Long userId, Long projectId, Long workId, String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (query == null) {
            return jdbcTemplate.query(
                """
                    select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                        word_count, source_type, version, status, created_at, updated_at
                    from ai_project_chapter
                    where user_id = ? and project_id = ? and work_id = ? and status <> 'ARCHIVED'
                    order by chapter_no asc, version asc, chapter_id asc
                    limit ?
                    """,
                chapterMapper(),
                userId,
                projectId,
                workId,
                safeLimit
            );
        }
        String like = "%" + query + "%";
        return jdbcTemplate.query(
            """
                select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                    word_count, source_type, version, status, created_at, updated_at
                from ai_project_chapter
                where user_id = ? and project_id = ? and work_id = ? and status <> 'ARCHIVED'
                  and (title like ? or content like ?)
                order by chapter_no asc, version asc, chapter_id asc
                limit ?
                """,
            chapterMapper(),
            userId,
            projectId,
            workId,
            like,
            like,
            safeLimit
        );
    }

    private List<Map<String, Object>> queryStructured(String sql,
                                                      Long userId,
                                                      Long projectId,
                                                      Long workId,
                                                      String query,
                                                      int limit,
                                                      String... fieldPairs) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String normalizedQuery = trimToNull(query, 200);
        String like = normalizedQuery == null ? null : "%" + normalizedQuery + "%";
        int likeCount = (int) sql.chars().filter(ch -> ch == '?').count() - 4;
        Object[] args = new Object[4 + likeCount];
        args[0] = userId;
        args[1] = projectId;
        args[2] = workId;
        args[3] = normalizedQuery;
        for (int i = 0; i < likeCount - 1; i++) {
            args[4 + i] = like;
        }
        args[args.length - 1] = safeLimit;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = mapRow(
                "projectId", rs.getLong("project_id"),
                "workId", rs.getLong("work_id"),
                "confidence", columnValue(rs, "confidence")
            );
            for (int i = 0; i + 1 < fieldPairs.length; i += 2) {
                row.put(fieldPairs[i], columnValue(rs, fieldPairs[i + 1]));
            }
            return row;
        }, args);
    }

    private Object columnValue(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return value;
    }

    private Map<String, Object> mapVectorChunk(ResultSet rs) throws SQLException {
        return mapRow(
            "chunkId", rs.getLong("id"),
            "projectId", rs.getLong("project_id"),
            "workId", rs.getLong("work_id"),
            "chapterId", columnValue(rs, "chapter_id"),
            "sceneId", columnValue(rs, "scene_id"),
            "sourceType", rs.getString("source_type"),
            "sourceId", columnValue(rs, "source_id"),
            "qdrantPointId", rs.getString("qdrant_point_id"),
            "chunkText", columnValue(rs, "chunk_text"),
            "visibility", rs.getString("visibility")
        );
    }

    private List<Map<String, Object>> searchVectorChunksByQdrant(Long userId,
                                                                  Long projectId,
                                                                  Long workId,
                                                                  String query,
                                                                  int limit) {
        if (embeddingClient == null || qdrantClient == null) {
            return List.of();
        }
        try {
            List<Double> vector = embeddingClient.embed(query);
            qdrantClient.ensureCollection();
            List<QdrantClient.SearchResult> results = qdrantClient.search(
                vector,
                Map.of(
                    "user_id", userId,
                    "project_id", projectId,
                    "work_id", workId,
                    "visibility", "private"
                ),
                limit
            );
            List<Map<String, Object>> mapped = new ArrayList<>();
            for (QdrantClient.SearchResult result : results) {
                Map<String, Object> row = mapQdrantProjectChunkResult(userId, projectId, workId, result);
                if (row != null) {
                    mapped.add(row);
                }
            }
            return mapped;
        } catch (RuntimeException ex) {
            LOGGER.warn("project vector search fallback to lexical: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> mapQdrantProjectChunkResult(Long userId,
                                                            Long projectId,
                                                            Long workId,
                                                            QdrantClient.SearchResult result) {
        Long chunkId = longPayload(result.payload(), "project_vector_chunk_id");
        if (chunkId == null) {
            chunkId = longPayload(result.payload(), "projectVectorChunkId");
        }
        if (chunkId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
                select id, project_id, work_id, chapter_id, scene_id, source_type, source_id,
                    qdrant_point_id, chunk_text, visibility
                from ai_project_vector_chunk
                where id = ? and user_id = ? and project_id = ? and work_id = ? and visibility = 'private'
                limit 1
                """,
            (rs, rowNum) -> mapVectorChunk(rs),
            chunkId,
            userId,
            projectId,
            workId
        );
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        row.put("retrievalBackend", "qdrant");
        row.put("score", result.score());
        return row;
    }

    private Long longPayload(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return null;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<Map<String, Object>> markRetrievalBackend(List<Map<String, Object>> rows, String retrievalBackend) {
        for (Map<String, Object> row : rows) {
            row.put("retrievalBackend", retrievalBackend);
        }
        return rows;
    }

    private void upsertProjectVectorChunk(ProjectChapterVO chapter,
                                          Long vectorChunkId,
                                          Long sceneId,
                                          String sourceType,
                                          Long sourceId,
                                          String contentHash,
                                          String qdrantPointId,
                                          String text) {
        if (embeddingClient == null || qdrantClient == null || text == null || text.isBlank()) {
            return;
        }
        try {
            List<Double> vector = embeddingClient.embed(trimForStorage(text, 8000));
            qdrantClient.ensureCollection();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user_id", chapter.getUserId());
            payload.put("project_id", chapter.getProjectId());
            payload.put("work_id", chapter.getWorkId());
            payload.put("chapter_id", chapter.getChapterId());
            payload.put("chapter_no", chapter.getChapterNo());
            putIfNotNull(payload, "scene_id", sceneId);
            payload.put("source_type", sourceType);
            putIfNotNull(payload, "source_id", sourceId);
            payload.put("content_hash", contentHash);
            payload.put("visibility", "private");
            payload.put("project_vector_chunk_id", vectorChunkId);
            payload.put("chunk_text_preview", trimForStorage(text, 1000));
            qdrantClient.upsertPoint(qdrantPointId, vector, payload);
        } catch (RuntimeException ex) {
            LOGGER.warn("project vector chunk upsert skipped: chunkId={}, reason={}: {}",
            vectorChunkId, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String projectVectorPointId(String seed) {
        return UUID.nameUUIDFromBytes(("project-vector-chunk:" + seed).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Map<String, Object> resolvedWork(ProjectWorkVO work) {
        Map<String, Object> resolved = workCandidate(work);
        resolved.put("status", "resolved");
        return resolved;
    }

    private Map<String, Object> workCandidate(ProjectWorkVO work) {
        return mapRow(
            "workId", work.getWorkId(),
            "projectId", work.getProjectId(),
            "userId", work.getUserId(),
            "title", work.getTitle(),
            "alias", work.getAlias(),
            "genre", work.getGenre(),
            "workStatus", work.getStatus()
        );
    }

    private Map<String, Object> mapRow(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    private ProjectWorkVO findOwnedWork(Long projectId, Long workId, Long userId) {
        projectService.ensureOwned(projectId, userId);
        List<ProjectWorkVO> works = jdbcTemplate.query(
            """
                select work_id, user_id, project_id, title, alias, genre, status, created_at, updated_at
                from ai_project_work
                where project_id = ? and work_id = ? and user_id = ? and status <> 'ARCHIVED'
                """,
            workMapper(),
            projectId,
            workId,
            userId
        );
        if (works.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "work not found");
        }
        return works.get(0);
    }

    private ProjectChapterVO findOwnedChapter(Long projectId, Long workId, Long chapterId, Long userId) {
        List<ProjectChapterVO> chapters = jdbcTemplate.query(
            """
                select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash,
                    word_count, source_type, version, status, created_at, updated_at
                from ai_project_chapter
                where project_id = ? and work_id = ? and chapter_id = ? and user_id = ?
                """,
            chapterMapper(),
            projectId,
            workId,
            chapterId,
            userId
        );
        if (chapters.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chapter not found");
        }
        return chapters.get(0);
    }

    private int nextChapterVersion(Long workId, int chapterNo) {
        Integer version = jdbcTemplate.queryForObject(
            "select coalesce(max(version), 0) + 1 from ai_project_chapter where work_id = ? and chapter_no = ?",
            Integer.class,
            workId,
            chapterNo
        );
        return version == null ? 1 : version;
    }

    private void createIngestArtifacts(ProjectChapterVO chapter) {
        if (chapter == null || chapter.getChapterId() == null) {
            return;
        }
        if (hasIngestArtifacts(chapter.getChapterId())) {
            return;
        }
        List<SceneSlice> scenes = splitScenes(chapter.getContent());
        List<Long> sceneIds = new ArrayList<>();
        for (SceneSlice scene : scenes) {
            Long sceneId = insertScene(chapter, scene);
            sceneIds.add(sceneId);
            insertVectorChunk(
                chapter,
                sceneId,
                "scene",
                sceneId,
                scene.text(),
                "project-" + chapter.getProjectId() + "-work-" + chapter.getWorkId()
                    + "-chapter-" + chapter.getChapterId() + "-scene-" + sceneId
            );
        }
        insertVectorChunk(
            chapter,
            null,
            "chapter",
            chapter.getChapterId(),
            chapter.getContent(),
            "project-" + chapter.getProjectId() + "-work-" + chapter.getWorkId()
                + "-chapter-" + chapter.getChapterId() + "-full"
        );
        extractWorldRules(chapter);
        extractForeshadowings(chapter);
        extractTimelineEvents(chapter, sceneIds);
        extractCharacterState(chapter);
        insertIngestJob(chapter, scenes.size());
    }

    private boolean hasIngestArtifacts(Long chapterId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_ingest_job where chapter_id = ? and status = 'COMPLETED'",
            Integer.class,
            chapterId
        );
        return count != null && count > 0;
    }

    private List<SceneSlice> splitScenes(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return List.of(new SceneSlice(1, 0, 0, ""));
        }
        List<SceneSlice> scenes = new ArrayList<>();
        int searchFrom = 0;
        for (String paragraph : PARAGRAPH_SPLIT.split(text)) {
            String sceneText = paragraph.trim();
            if (sceneText.isEmpty()) {
                continue;
            }
            int start = Math.max(0, text.indexOf(sceneText, searchFrom));
            int end = Math.min(text.length(), start + sceneText.length());
            searchFrom = end;
            addSceneSlices(scenes, sceneText, start);
        }
        if (scenes.isEmpty()) {
            addSceneSlices(scenes, text, 0);
        }
        return scenes;
    }

    private void addSceneSlices(List<SceneSlice> scenes, String text, int baseOffset) {
        if (text.length() <= SCENE_TARGET_CHARS) {
            scenes.add(new SceneSlice(scenes.size() + 1, baseOffset, baseOffset + text.length(), text));
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + SCENE_TARGET_CHARS);
            if (end < text.length()) {
                int sentenceEnd = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('.', end));
                if (sentenceEnd > start + 200) {
                    end = sentenceEnd + 1;
                }
            }
            String slice = text.substring(start, end).trim();
            if (!slice.isEmpty()) {
                scenes.add(new SceneSlice(scenes.size() + 1, baseOffset + start, baseOffset + end, slice));
            }
            start = end;
        }
    }

    private Long insertScene(ProjectChapterVO chapter, SceneSlice scene) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                    insert into ai_project_scene(user_id, project_id, work_id, chapter_id, scene_no,
                        summary, start_offset, end_offset, confidence)
                    values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                new String[]{"scene_id"}
            );
            statement.setLong(1, chapter.getUserId());
            statement.setLong(2, chapter.getProjectId());
            statement.setLong(3, chapter.getWorkId());
            statement.setLong(4, chapter.getChapterId());
            statement.setInt(5, scene.sceneNo());
            statement.setString(6, summarize(scene.text(), 280));
            statement.setInt(7, scene.startOffset());
            statement.setInt(8, scene.endOffset());
            statement.setDouble(9, 0.70d);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "scene id missing");
        }
        return key.longValue();
    }

    private void insertVectorChunk(ProjectChapterVO chapter,
                                   Long sceneId,
                                   String sourceType,
                                   Long sourceId,
                                   String text,
                                   String pointId) {
        String contentHash = sha256((text == null ? "" : text) + "|" + pointId);
        String qdrantPointId = projectVectorPointId(pointId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                    insert into ai_project_vector_chunk(user_id, project_id, work_id, chapter_id, scene_id,
                        source_type, source_id, content_hash, qdrant_point_id, chunk_text, visibility)
                    values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'private')
                    """,
                new String[]{"id"}
            );
            statement.setLong(1, chapter.getUserId());
            statement.setLong(2, chapter.getProjectId());
            statement.setLong(3, chapter.getWorkId());
            statement.setLong(4, chapter.getChapterId());
            if (sceneId == null) {
                statement.setObject(5, null);
            } else {
                statement.setLong(5, sceneId);
            }
            statement.setString(6, sourceType);
            if (sourceId == null) {
                statement.setObject(7, null);
            } else {
                statement.setLong(7, sourceId);
            }
            statement.setString(8, contentHash);
            statement.setString(9, qdrantPointId);
            statement.setString(10, trimForStorage(text, 4000));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "project vector chunk id missing");
        }
        upsertProjectVectorChunk(
            chapter,
            key.longValue(),
            sceneId,
            sourceType,
            sourceId,
            contentHash,
            qdrantPointId,
            text
        );
    }

    private void extractWorldRules(ProjectChapterVO chapter) {
        List<String> candidates = linesMatching(
            chapter.getContent(),
            List.of("System rule", "系统规则", "设定", "规则", "金手指", "三端", "terminal")
        );
        for (String candidate : candidates) {
            jdbcTemplate.update(
                """
                    insert into ai_project_world_rule(user_id, project_id, work_id, rule_type, title,
                        content, first_chapter_no, status, confidence)
                    values(?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """,
                chapter.getUserId(),
                chapter.getProjectId(),
                chapter.getWorkId(),
                "system_rule",
                summarize(candidate, 120),
                candidate,
                chapter.getChapterNo(),
                0.78d
            );
        }
    }

    private void extractForeshadowings(ProjectChapterVO chapter) {
        List<String> candidates = linesMatching(
            chapter.getContent(),
            List.of("Foreshadowing", "伏笔", "暗线", "未回收", "未解", "神秘", "unknown", "unresolved")
        );
        for (String candidate : candidates) {
            jdbcTemplate.update(
                """
                    insert into ai_project_foreshadowing(user_id, project_id, work_id, title, content,
                        status, planted_chapter_no, importance, evidence_refs, confidence)
                    values(?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)
                    """,
                chapter.getUserId(),
                chapter.getProjectId(),
                chapter.getWorkId(),
                summarize(candidate, 120),
                candidate,
                chapter.getChapterNo(),
                "medium",
                "{\"chapterId\":" + chapter.getChapterId() + "}",
                0.76d
            );
        }
    }

    private void extractTimelineEvents(ProjectChapterVO chapter, List<Long> sceneIds) {
        List<String> candidates = linesMatching(
            chapter.getContent(),
            List.of("Timeline event", "时间线", "事件", "完成", "开启", "交付", "receives", "completes")
        );
        if (candidates.isEmpty()) {
            candidates = List.of(summarize(chapter.getContent(), 220));
        }
        int order = 1;
        for (String candidate : candidates) {
            Long sceneId = sceneIds.isEmpty() ? null : sceneIds.get(Math.min(sceneIds.size() - 1, order - 1));
            jdbcTemplate.update(
                """
                    insert into ai_project_timeline_event(user_id, project_id, work_id, chapter_id,
                        chapter_no, scene_id, event_order, title, summary, causal_refs, confidence)
                    values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                chapter.getUserId(),
                chapter.getProjectId(),
                chapter.getWorkId(),
                chapter.getChapterId(),
                chapter.getChapterNo(),
                sceneId,
                order,
                summarize(candidate, 120),
                candidate,
                "{\"chapterId\":" + chapter.getChapterId() + "}",
                0.72d
            );
            order++;
        }
    }

    private void extractCharacterState(ProjectChapterVO chapter) {
        String content = chapter.getContent();
        if (content == null || content.isBlank()) {
            return;
        }
        String characterName = firstMentionedName(content);
        if (characterName == null) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_project_character_state(user_id, project_id, work_id, character_name,
                    chapter_id, chapter_no, state_summary, motivation, confidence)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            chapter.getUserId(),
            chapter.getProjectId(),
            chapter.getWorkId(),
            characterName,
            chapter.getChapterId(),
            chapter.getChapterNo(),
            summarize(content, 220),
            "imported_chapter_context",
            0.55d
        );
    }

    private void insertIngestJob(ProjectChapterVO chapter, int sceneCount) {
        String resultJson = "{\"sceneCount\":" + sceneCount + ",\"vectorScope\":\"project_work\"}";
        jdbcTemplate.update(
            """
                insert into ai_project_ingest_job(user_id, project_id, work_id, chapter_id,
                    job_type, status, progress, result_json)
                values(?, ?, ?, ?, 'chapter_import_parse', 'COMPLETED', 100, ?)
                """,
            chapter.getUserId(),
            chapter.getProjectId(),
            chapter.getWorkId(),
            chapter.getChapterId(),
            resultJson
        );
    }

    private List<String> linesMatching(String content, List<String> markers) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String block : PARAGRAPH_SPLIT.split(content.trim())) {
            String candidate = block.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (matchesAnyMarker(candidate, markers)) {
                matches.add(trimForStorage(candidate, 1000));
            }
        }
        if (!matches.isEmpty()) {
            return matches;
        }
        String[] lines = content.split("\\R+");
        for (String line : lines) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && matchesAnyMarker(candidate, markers)) {
                matches.add(trimForStorage(candidate, 1000));
            }
        }
        return matches;
    }

    private boolean matchesAnyMarker(String candidate, List<String> markers) {
        String lower = candidate.toLowerCase();
        for (String marker : markers) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String firstMentionedName(String content) {
        String[] knownNames = {"林周", "Lin Zhou", "主角", "男主", "女主"};
        for (String name : knownNames) {
            if (content.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private String summarize(String value, int maxChars) {
        String text = trimForStorage(value, maxChars);
        if (text == null || text.isBlank()) {
            return "Imported chapter context";
        }
        return text;
    }

    private String trimForStorage(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String text = value.replace("\r", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private RowMapper<ProjectWorkVO> workMapper() {
        return (rs, rowNum) -> {
            ProjectWorkVO vo = new ProjectWorkVO();
            vo.setWorkId(rs.getLong("work_id"));
            vo.setUserId(rs.getLong("user_id"));
            vo.setProjectId(rs.getLong("project_id"));
            vo.setTitle(rs.getString("title"));
            vo.setAlias(rs.getString("alias"));
            vo.setGenre(rs.getString("genre"));
            vo.setStatus(rs.getString("status"));
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            vo.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return vo;
        };
    }

    private RowMapper<ProjectChapterVO> chapterMapper() {
        return (rs, rowNum) -> {
            ProjectChapterVO vo = new ProjectChapterVO();
            vo.setChapterId(rs.getLong("chapter_id"));
            vo.setUserId(rs.getLong("user_id"));
            vo.setProjectId(rs.getLong("project_id"));
            vo.setWorkId(rs.getLong("work_id"));
            vo.setChapterNo(rs.getInt("chapter_no"));
            vo.setTitle(rs.getString("title"));
            vo.setContent(rs.getString("content"));
            vo.setContentHash(rs.getString("content_hash"));
            vo.setWordCount(rs.getInt("word_count"));
            vo.setSourceType(rs.getString("source_type"));
            vo.setVersion(rs.getInt("version"));
            vo.setStatus(rs.getString("status"));
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            vo.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return vo;
        };
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private String requireText(String value, String message, int maxChars) {
        String text = trimToNull(value, maxChars);
        if (text == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return text;
    }

    private String trimToNull(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (maxChars != Integer.MAX_VALUE && trimmed.length() > maxChars) {
            return trimmed.substring(0, maxChars);
        }
        return trimmed;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "sha256 unavailable");
        }
    }

    private record SceneSlice(int sceneNo, int startOffset, int endOffset, String text) {
    }
}

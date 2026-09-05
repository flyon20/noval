package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import com.novelanalyzer.modules.knowledge.vo.StoryGraphResultVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class KnowledgeStoryGraphService {

    private static final int DEFAULT_MAX_HOPS = 2;
    private static final int DEEP_MAX_HOPS = 3;
    private static final int MAX_EDGES_PER_HOP = 20;
    private static final int MAX_NODES = 60;
    private static final int MAX_PATHS = 30;
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Runnable NO_OP_CHECKPOINT = () -> {
    };

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeStoryGraphService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long upsertNode(NodeInput input) {
        requireNodeInput(input);
        String canonicalKey = canonicalKey(input.canonicalKey());
        List<Long> existing = jdbcTemplate.query(
            "select node_id from ai_project_story_node where user_id = ? and project_id = ? and work_id = ? and node_type = ? and canonical_key = ? and generation_id = ?",
            (rs, rowNum) -> rs.getLong(1),
            input.userId(), input.projectId(), input.workId(), input.nodeType(), canonicalKey, input.generationId()
        );
        if (!existing.isEmpty()) {
            Long nodeId = existing.get(0);
            jdbcTemplate.update(
                "update ai_project_story_node set source_chapter_id = ?, display_name = ?, aliases = ?, confidence = ?, status = 'ACTIVE', updated_at = current_timestamp where node_id = ?",
                input.sourceChapterId(), trim(input.displayName(), 500), aliasEnvelope(input.aliases()), input.confidence(), nodeId
            );
            return nodeId;
        }
        try {
            return insertNode(input, canonicalKey);
        } catch (DuplicateKeyException ex) {
            List<Long> concurrent = jdbcTemplate.query(
                "select node_id from ai_project_story_node where user_id = ? and project_id = ? and work_id = ? and node_type = ? and canonical_key = ? and generation_id = ?",
                (rs, rowNum) -> rs.getLong(1),
                input.userId(), input.projectId(), input.workId(), input.nodeType(), canonicalKey, input.generationId()
            );
            if (!concurrent.isEmpty()) {
                return concurrent.get(0);
            }
            throw ex;
        }
    }

    public List<Long> upsertRelation(EdgeInput input) {
        requireEdgeInput(input);
        requireNodeInScope(input.fromNodeId(), input);
        requireNodeInScope(input.toNodeId(), input);
        List<Long> edgeIds = new ArrayList<>();
        edgeIds.add(upsertDirectedEdge(input, input.fromNodeId(), input.toNodeId()));
        if (input.symmetric() && !input.fromNodeId().equals(input.toNodeId())) {
            edgeIds.add(upsertDirectedEdge(input, input.toNodeId(), input.fromNodeId()));
        }
        return edgeIds;
    }

    public StoryGraphResultVO traverse(Long userId,
                                       Long projectId,
                                       Long workId,
                                       Collection<String> entityTerms,
                                       boolean deep) {
        return traverse(userId, projectId, workId, entityTerms, deep, 300);
    }

    public StoryGraphResultVO traverse(Long userId,
                                       Long projectId,
                                       Long workId,
                                       Collection<String> entityTerms,
                                       boolean deep,
                                       int budgetMillis) {
        requireScope(userId, projectId, workId);
        long startedAt = System.nanoTime();
        long sqlBudgetNanos = Math.max(1, Math.min(300, budgetMillis)) * 1_000_000L;
        LinkedHashSet<String> gaps = new LinkedHashSet<>();
        LinkedHashMap<Long, Map<String, Object>> nodesById = new LinkedHashMap<>();
        LinkedHashMap<Long, Map<String, Object>> edgesById = new LinkedHashMap<>();
        List<Map<String, Object>> paths = new ArrayList<>();

        List<Map<String, Object>> seeds = findSeedNodes(userId, projectId, workId, entityTerms);
        if (seeds.isEmpty()) {
            return graphResult(nodesById, edgesById, paths, gaps, false);
        }
        Map<Long, List<Long>> pathsByNode = new LinkedHashMap<>();
        Set<Long> visited = new LinkedHashSet<>();
        ArrayDeque<Long> frontier = new ArrayDeque<>();
        for (Map<String, Object> seed : seeds) {
            Long nodeId = longValue(seed.get("nodeId"));
            if (nodeId == null || nodesById.size() >= MAX_NODES) {
                continue;
            }
            nodesById.put(nodeId, seed);
            visited.add(nodeId);
            frontier.add(nodeId);
            pathsByNode.put(nodeId, List.of(nodeId));
        }

        int hopLimit = deep ? DEEP_MAX_HOPS : DEFAULT_MAX_HOPS;
        boolean partial = false;
        for (int hop = 0; hop < hopLimit && !frontier.isEmpty(); hop++) {
            if (System.nanoTime() - startedAt > sqlBudgetNanos) {
                gaps.add("graph_sql_budget_exhausted");
                partial = true;
                break;
            }
            List<Long> currentFrontier = new ArrayList<>(frontier);
            frontier.clear();
            List<Map<String, Object>> edges = findAdjacentEdges(userId, projectId, workId, currentFrontier, MAX_EDGES_PER_HOP);
            if (edges.size() >= MAX_EDGES_PER_HOP) {
                gaps.add("graph_edge_budget_exhausted");
                partial = true;
            }
            Set<Long> neighborIds = new LinkedHashSet<>();
            for (Map<String, Object> edge : edges) {
                Long edgeId = longValue(edge.get("edgeId"));
                Long fromNodeId = longValue(edge.get("fromNodeId"));
                Long toNodeId = longValue(edge.get("toNodeId"));
                if (edgeId == null || fromNodeId == null || toNodeId == null || edge.get("evidenceChapterId") == null) {
                    continue;
                }
                edgesById.putIfAbsent(edgeId, edge);
                if (currentFrontier.contains(fromNodeId)) {
                    neighborIds.add(toNodeId);
                }
                if (currentFrontier.contains(toNodeId)) {
                    neighborIds.add(fromNodeId);
                }
            }
            if (neighborIds.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> neighbors = findVisibleNodesById(userId, projectId, workId, neighborIds);
            Map<Long, Map<String, Object>> neighborById = new LinkedHashMap<>();
            for (Map<String, Object> neighbor : neighbors) {
                Long nodeId = longValue(neighbor.get("nodeId"));
                if (nodeId != null) {
                    neighborById.put(nodeId, neighbor);
                }
            }
            for (Long neighborId : neighborIds) {
                if (visited.contains(neighborId)) {
                    continue;
                }
                if (nodesById.size() >= MAX_NODES) {
                    gaps.add("graph_node_budget_exhausted");
                    partial = true;
                    break;
                }
                Map<String, Object> neighbor = neighborById.get(neighborId);
                if (neighbor == null) {
                    continue;
                }
                Long parentId = parentForNeighbor(currentFrontier, edges, neighborId);
                List<Long> parentPath = parentId == null ? List.of() : pathsByNode.getOrDefault(parentId, List.of(parentId));
                List<Long> nextPath = new ArrayList<>(parentPath);
                nextPath.add(neighborId);
                nodesById.put(neighborId, neighbor);
                visited.add(neighborId);
                frontier.add(neighborId);
                pathsByNode.put(neighborId, List.copyOf(nextPath));
                if (paths.size() < MAX_PATHS) {
                    paths.add(path(nextPath));
                } else {
                    gaps.add("graph_path_budget_exhausted");
                    partial = true;
                }
            }
        }
        return graphResult(nodesById, edgesById, paths, gaps, partial);
    }

    public IndexCounts indexGeneration(ProjectChapterVO chapter, Long generationId) {
        return indexGeneration(chapter, generationId, NO_OP_CHECKPOINT);
    }

    public IndexCounts indexGeneration(ProjectChapterVO chapter,
                                       Long generationId,
                                       Runnable ownershipCheckpoint) {
        if (chapter == null || generationId == null || chapter.getChapterId() == null) {
            return new IndexCounts(0, 0);
        }
        Runnable checkpoint = ownershipCheckpoint == null ? NO_OP_CHECKPOINT : ownershipCheckpoint;
        checkpoint.run();
        int documents = 0;
        int nodes = 0;
        Long chapterNodeId = upsertNode(new NodeInput(
            chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, chapter.getChapterId(),
            "CHAPTER", "chapter:" + chapter.getChapterId(), titleForChapter(chapter), aliases(chapter.getTitle()), 1.0d
        ));
        nodes++;
        checkpoint.run();
        upsertSearchDocument(new SearchDocumentInput(
            chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId,
            chapter.getVersion(), null, chapter.getChapterId(), "CHAPTER", "chapter:" + chapter.getChapterId(),
            chapter.getTitle(), aliases(chapter.getTitle()), chapter.getContent(), chapter.getContentHash(), 1.0d
        ));
        documents++;

        checkpoint.run();
        List<Map<String, Object>> scenes = jdbcTemplate.query(
            "select scene_id, scene_no, summary, confidence from ai_project_scene where user_id = ? and project_id = ? and work_id = ? and chapter_id = ? and generation_id = ? and status = 'ACTIVE' order by scene_no asc, scene_id asc",
            (rs, rowNum) -> row(
                "id", rs.getLong("scene_id"), "sceneNo", rs.getInt("scene_no"), "content", rs.getString("summary"),
                "confidence", decimal(rs, "confidence")
            ), chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId
        );
        for (Map<String, Object> scene : scenes) {
            checkpoint.run();
            Long sceneId = longValue(scene.get("id"));
            if (sceneId == null) {
                continue;
            }
            String sceneTitle = titleForChapter(chapter) + " scene " + scene.get("sceneNo");
            upsertSearchDocument(new SearchDocumentInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId,
                chapter.getVersion(), sceneId, sceneId, "SCENE", "scene:" + sceneId, sceneTitle,
                List.of("scene-" + scene.get("sceneNo")), stringValue(scene.get("content")), hash("scene:" + sceneId + ":" + stringValue(scene.get("content"))),
                doubleValue(scene.get("confidence"))
            ));
            Long sceneNodeId = upsertNode(new NodeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, chapter.getChapterId(),
                "SCENE", "scene:" + sceneId, sceneTitle, List.of("scene-" + scene.get("sceneNo")), doubleValue(scene.get("confidence"))
            ));
            upsertRelation(new EdgeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, sceneNodeId, chapterNodeId,
                "OCCURS_IN", "evidence", chapter.getChapterId(), sceneId, "scene:" + sceneId,
                chapter.getChapterNo(), chapter.getChapterNo(), doubleValue(scene.get("confidence")), false
            ));
            documents++;
            nodes++;
        }
        IndexCounts characterCounts = indexCharacterStates(chapter, generationId, chapterNodeId, checkpoint);
        IndexCounts worldRuleCounts = indexWorldRules(chapter, generationId, chapterNodeId, checkpoint);
        IndexCounts foreshadowingCounts = indexForeshadowings(chapter, generationId, chapterNodeId, checkpoint);
        IndexCounts timelineCounts = indexTimelineEvents(chapter, generationId, chapterNodeId, checkpoint);
        documents += characterCounts.documentCount() + worldRuleCounts.documentCount()
            + foreshadowingCounts.documentCount() + timelineCounts.documentCount();
        nodes += characterCounts.nodeCount() + worldRuleCounts.nodeCount()
            + foreshadowingCounts.nodeCount() + timelineCounts.nodeCount();
        checkpoint.run();
        return new IndexCounts(documents, nodes);
    }

    private IndexCounts indexCharacterStates(ProjectChapterVO chapter,
                                             Long generationId,
                                             Long chapterNodeId,
                                             Runnable ownershipCheckpoint) {
        ownershipCheckpoint.run();
        List<Map<String, Object>> states = jdbcTemplate.query(
            "select state_id, character_name, state_summary, scene_id, confidence from ai_project_character_state where user_id = ? and project_id = ? and work_id = ? and chapter_id = ? and generation_id = ? and status = 'ACTIVE' order by state_id asc",
            (rs, rowNum) -> row("id", rs.getLong("state_id"), "name", rs.getString("character_name"),
                "content", rs.getString("state_summary"), "sceneId", nullableLong(rs, "scene_id"), "confidence", decimal(rs, "confidence")),
            chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId
        );
        for (Map<String, Object> state : states) {
            ownershipCheckpoint.run();
            Long stateId = longValue(state.get("id"));
            String name = stringValue(state.get("name"));
            if (stateId == null || name == null) {
                continue;
            }
            Double confidence = doubleValue(state.get("confidence"));
            upsertSearchDocument(new SearchDocumentInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId,
                chapter.getVersion(), longValue(state.get("sceneId")), stateId, "CHARACTER_STATE", "character-state:" + stateId,
                name, aliases(name), stringValue(state.get("content")), hash("character-state:" + stateId + ":" + stringValue(state.get("content"))), confidence
            ));
            Long personNodeId = upsertNode(new NodeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, chapter.getChapterId(),
                "PERSON", "person:" + name, name, aliases(name), confidence
            ));
            upsertRelation(new EdgeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, personNodeId, chapterNodeId,
                "APPEARS_IN", "character", chapter.getChapterId(), longValue(state.get("sceneId")), "character-state:" + stateId,
                chapter.getChapterNo(), chapter.getChapterNo(), confidence, false
            ));
        }
        return new IndexCounts(states.size(), states.size());
    }

    private IndexCounts indexWorldRules(ProjectChapterVO chapter,
                                        Long generationId,
                                        Long chapterNodeId,
                                        Runnable ownershipCheckpoint) {
        ownershipCheckpoint.run();
        List<Map<String, Object>> rules = jdbcTemplate.query(
            "select rule_id, title, content, confidence from ai_project_world_rule where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and status = 'ACTIVE' and status_proj = 'ACTIVE' order by rule_id asc",
            (rs, rowNum) -> row("id", rs.getLong("rule_id"), "title", rs.getString("title"), "content", rs.getString("content"), "confidence", decimal(rs, "confidence")),
            chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId
        );
        for (Map<String, Object> rule : rules) {
            ownershipCheckpoint.run();
            Long ruleId = longValue(rule.get("id"));
            String title = stringValue(rule.get("title"));
            if (ruleId == null || title == null) {
                continue;
            }
            Double confidence = doubleValue(rule.get("confidence"));
            upsertSearchDocument(new SearchDocumentInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId,
                chapter.getVersion(), null, ruleId, "WORLD_RULE", "world-rule:" + ruleId, title, aliases(title),
                stringValue(rule.get("content")), hash("world-rule:" + ruleId + ":" + stringValue(rule.get("content"))), confidence
            ));
            Long ruleNodeId = upsertNode(new NodeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, chapter.getChapterId(),
                "WORLD_RULE", "world-rule:" + title, title, aliases(title), confidence
            ));
            upsertRelation(new EdgeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, ruleNodeId, chapterNodeId,
                "DEFINED_IN", "setting", chapter.getChapterId(), null, "world-rule:" + ruleId,
                chapter.getChapterNo(), chapter.getChapterNo(), confidence, false
            ));
        }
        return new IndexCounts(rules.size(), rules.size());
    }

    private IndexCounts indexForeshadowings(ProjectChapterVO chapter,
                                            Long generationId,
                                            Long chapterNodeId,
                                            Runnable ownershipCheckpoint) {
        ownershipCheckpoint.run();
        List<Map<String, Object>> items = jdbcTemplate.query(
            "select foreshadowing_id, title, content, planted_chapter_no, paid_off_chapter_no, confidence from ai_project_foreshadowing where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and status <> 'ARCHIVED' order by foreshadowing_id asc",
            (rs, rowNum) -> row("id", rs.getLong("foreshadowing_id"), "title", rs.getString("title"), "content", rs.getString("content"),
                "from", nullableInt(rs, "planted_chapter_no"), "to", nullableInt(rs, "paid_off_chapter_no"), "confidence", decimal(rs, "confidence")),
            chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId
        );
        for (Map<String, Object> item : items) {
            ownershipCheckpoint.run();
            Long id = longValue(item.get("id"));
            String title = stringValue(item.get("title"));
            if (id == null || title == null) {
                continue;
            }
            Double confidence = doubleValue(item.get("confidence"));
            upsertSearchDocument(new SearchDocumentInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId,
                chapter.getVersion(), null, id, "FORESHADOWING", "foreshadowing:" + id, title, aliases(title),
                stringValue(item.get("content")), hash("foreshadowing:" + id + ":" + stringValue(item.get("content"))), confidence
            ));
            Long nodeId = upsertNode(new NodeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, chapter.getChapterId(),
                "FORESHADOWING", "foreshadowing:" + title, title, aliases(title), confidence
            ));
            upsertRelation(new EdgeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, nodeId, chapterNodeId,
                "PLANTED_IN", "foreshadowing", chapter.getChapterId(), null, "foreshadowing:" + id,
                nullableInt(item.get("from")), nullableInt(item.get("to")), confidence, false
            ));
        }
        return new IndexCounts(items.size(), items.size());
    }

    private IndexCounts indexTimelineEvents(ProjectChapterVO chapter,
                                            Long generationId,
                                            Long chapterNodeId,
                                            Runnable ownershipCheckpoint) {
        ownershipCheckpoint.run();
        List<Map<String, Object>> events = jdbcTemplate.query(
            "select event_id, title, summary, scene_id, confidence from ai_project_timeline_event where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and status = 'ACTIVE' order by event_id asc",
            (rs, rowNum) -> row("id", rs.getLong("event_id"), "title", rs.getString("title"), "content", rs.getString("summary"),
                "sceneId", nullableLong(rs, "scene_id"), "confidence", decimal(rs, "confidence")),
            chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId
        );
        for (Map<String, Object> event : events) {
            ownershipCheckpoint.run();
            Long id = longValue(event.get("id"));
            String title = stringValue(event.get("title"));
            if (id == null || title == null) {
                continue;
            }
            Double confidence = doubleValue(event.get("confidence"));
            upsertSearchDocument(new SearchDocumentInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), chapter.getChapterId(), generationId,
                chapter.getVersion(), longValue(event.get("sceneId")), id, "TIMELINE_EVENT", "timeline-event:" + id,
                title, aliases(title), stringValue(event.get("content")), hash("timeline-event:" + id + ":" + stringValue(event.get("content"))), confidence
            ));
            Long nodeId = upsertNode(new NodeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, chapter.getChapterId(),
                "EVENT", "event:" + title, title, aliases(title), confidence
            ));
            upsertRelation(new EdgeInput(
                chapter.getUserId(), chapter.getProjectId(), chapter.getWorkId(), generationId, nodeId, chapterNodeId,
                "OCCURS_IN", "timeline", chapter.getChapterId(), longValue(event.get("sceneId")), "timeline-event:" + id,
                chapter.getChapterNo(), chapter.getChapterNo(), confidence, false
            ));
        }
        return new IndexCounts(events.size(), events.size());
    }

    private Long insertNode(NodeInput input, String canonicalKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_story_node(user_id, project_id, work_id, generation_id, source_chapter_id, node_type, canonical_key, display_name, aliases, confidence, status) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                new String[]{"node_id"}
            );
            statement.setLong(1, input.userId());
            statement.setLong(2, input.projectId());
            statement.setLong(3, input.workId());
            statement.setLong(4, input.generationId());
            statement.setLong(5, input.sourceChapterId());
            statement.setString(6, trim(input.nodeType(), 40));
            statement.setString(7, canonicalKey);
            statement.setString(8, trim(input.displayName(), 500));
            statement.setString(9, aliasEnvelope(input.aliases()));
            statement.setObject(10, input.confidence());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "story node id missing");
        }
        return key.longValue();
    }

    private Long upsertDirectedEdge(EdgeInput input, Long fromNodeId, Long toNodeId) {
        String edgeKey = edgeKey(input, fromNodeId, toNodeId);
        boolean disputed = hasConflictingRelation(input, fromNodeId, toNodeId);
        String status = disputed ? "DISPUTED" : "ACTIVE";
        if (disputed) {
            jdbcTemplate.update(
                "update ai_project_story_edge set status = 'DISPUTED', updated_at = current_timestamp where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and from_node_id = ? and to_node_id = ? and relation_group = ? and relation_type <> ? and status = 'ACTIVE'",
                input.userId(), input.projectId(), input.workId(), input.generationId(), fromNodeId, toNodeId, input.relationGroup(), input.relationType()
            );
        }
        List<Long> existing = jdbcTemplate.query(
            "select edge_id from ai_project_story_edge where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and edge_key = ?",
            (rs, rowNum) -> rs.getLong(1), input.userId(), input.projectId(), input.workId(), input.generationId(), edgeKey
        );
        if (!existing.isEmpty()) {
            Long edgeId = existing.get(0);
            jdbcTemplate.update(
                "update ai_project_story_edge set evidence_scene_id = ?, evidence_ref = ?, valid_from_chapter_no = ?, valid_to_chapter_no = ?, confidence = ?, status = ?, updated_at = current_timestamp where edge_id = ?",
                input.evidenceSceneId(), trim(input.evidenceRef(), 500), input.validFromChapterNo(), input.validToChapterNo(), input.confidence(), status, edgeId
            );
            return edgeId;
        }
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    "insert into ai_project_story_edge(user_id, project_id, work_id, generation_id, edge_key, from_node_id, to_node_id, relation_type, relation_group, evidence_chapter_id, evidence_scene_id, evidence_ref, valid_from_chapter_no, valid_to_chapter_no, confidence, status) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"edge_id"}
                );
                statement.setLong(1, input.userId());
                statement.setLong(2, input.projectId());
                statement.setLong(3, input.workId());
                statement.setLong(4, input.generationId());
                statement.setString(5, edgeKey);
                statement.setLong(6, fromNodeId);
                statement.setLong(7, toNodeId);
                statement.setString(8, trim(input.relationType(), 80));
                statement.setString(9, trim(input.relationGroup(), 80));
                statement.setLong(10, input.evidenceChapterId());
                statement.setObject(11, input.evidenceSceneId());
                statement.setString(12, trim(input.evidenceRef(), 500));
                statement.setObject(13, input.validFromChapterNo());
                statement.setObject(14, input.validToChapterNo());
                statement.setObject(15, input.confidence());
                statement.setString(16, status);
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "story edge id missing");
            }
            return key.longValue();
        } catch (DuplicateKeyException ex) {
            List<Long> concurrent = jdbcTemplate.query(
                "select edge_id from ai_project_story_edge where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and edge_key = ?",
                (rs, rowNum) -> rs.getLong(1), input.userId(), input.projectId(), input.workId(), input.generationId(), edgeKey
            );
            if (!concurrent.isEmpty()) {
                return concurrent.get(0);
            }
            throw ex;
        }
    }

    private boolean hasConflictingRelation(EdgeInput input, Long fromNodeId, Long toNodeId) {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*) from ai_project_story_edge
                where user_id = ? and project_id = ? and work_id = ? and generation_id = ?
                  and from_node_id = ? and to_node_id = ? and relation_group = ? and relation_type <> ?
                  and status in ('ACTIVE', 'DISPUTED')
                  and (valid_to_chapter_no is null or ? is null or valid_to_chapter_no >= ?)
                  and (valid_from_chapter_no is null or ? is null or valid_from_chapter_no <= ?)
                """,
            Integer.class,
            input.userId(), input.projectId(), input.workId(), input.generationId(), fromNodeId, toNodeId,
            input.relationGroup(), input.relationType(), input.validFromChapterNo(), input.validFromChapterNo(),
            input.validToChapterNo(), input.validToChapterNo()
        );
        return count != null && count > 0;
    }


    public StoryGraphResultVO snapshotForWork(Long userId, Long projectId, Long workId, Integer nodeLimit) {
        requireScope(userId, projectId, workId);
        int limit = nodeLimit == null ? MAX_NODES : Math.max(1, Math.min(nodeLimit, MAX_NODES));
        List<Map<String, Object>> nodes = jdbcTemplate.query(
            "select n.node_id, n.node_type, n.display_name, n.source_chapter_id, n.confidence, n.status, n.generation_id " +
                "from ai_project_story_node n " +
                "join ai_project_ingest_generation g on g.generation_id = n.generation_id and g.status = 'ACTIVE' " +
                "where n.user_id = ? and n.project_id = ? and n.work_id = ? and n.status = 'ACTIVE' " +
                "and exists (select 1 from ai_project_chapter_head h where h.user_id = n.user_id and h.project_id = n.project_id and h.work_id = n.work_id " +
                "and h.active_generation_id = n.generation_id and h.tombstoned_at is null) " +
                "order by n.node_id desc limit ?",
            (rs, rowNum) -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("nodeId", rs.getLong("node_id"));
                node.put("id", rs.getLong("node_id"));
                node.put("nodeType", rs.getString("node_type"));
                node.put("category", rs.getString("node_type"));
                node.put("name", rs.getString("display_name"));
                node.put("displayName", rs.getString("display_name"));
                node.put("sourceChapterId", nullableLong(rs, "source_chapter_id"));
                node.put("confidence", decimal(rs, "confidence"));
                node.put("status", rs.getString("status"));
                node.put("generationId", nullableLong(rs, "generation_id"));
                node.put("value", 1);
                return node;
            },
            userId, projectId, workId, limit
        );
        LinkedHashMap<Long, Map<String, Object>> nodesById = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            Long nodeId = longValue(node.get("nodeId"));
            if (nodeId != null) {
                nodesById.put(nodeId, node);
            }
        }
        LinkedHashMap<Long, Map<String, Object>> edgesById = new LinkedHashMap<>();
        LinkedHashSet<String> gaps = new LinkedHashSet<>();
        if (nodesById.isEmpty()) {
            gaps.add("no_active_story_nodes");
            return graphResult(nodesById, edgesById, List.of(), gaps, false);
        }
        List<Long> nodeIds = new ArrayList<>(nodesById.keySet());
        String placeholders = placeholders(nodeIds.size());
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add(projectId);
        params.add(workId);
        params.addAll(nodeIds);
        params.addAll(nodeIds);
        params.add(Math.min(MAX_EDGES_PER_HOP * 3, 120));
        List<Map<String, Object>> edges = jdbcTemplate.query(
            "select e.edge_id, e.from_node_id, e.to_node_id, e.relation_type, e.confidence, e.evidence_chapter_id, e.generation_id " +
                "from ai_project_story_edge e " +
                "join ai_project_ingest_generation g on g.generation_id = e.generation_id and g.status = 'ACTIVE' " +
                "where e.user_id = ? and e.project_id = ? and e.work_id = ? and e.status = 'ACTIVE' " +
                "and e.from_node_id in (" + placeholders + ") and e.to_node_id in (" + placeholders + ") " +
                "and exists (select 1 from ai_project_chapter_head h where h.user_id = e.user_id and h.project_id = e.project_id and h.work_id = e.work_id " +
                "and h.active_generation_id = e.generation_id and h.tombstoned_at is null) " +
                "order by e.edge_id desc limit ?",
            (rs, rowNum) -> {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("edgeId", rs.getLong("edge_id"));
                edge.put("id", rs.getLong("edge_id"));
                edge.put("fromNodeId", rs.getLong("from_node_id"));
                edge.put("toNodeId", rs.getLong("to_node_id"));
                edge.put("source", String.valueOf(rs.getLong("from_node_id")));
                edge.put("target", String.valueOf(rs.getLong("to_node_id")));
                edge.put("relationType", rs.getString("relation_type"));
                edge.put("name", rs.getString("relation_type"));
                edge.put("confidence", decimal(rs, "confidence"));
                edge.put("evidenceChapterId", nullableLong(rs, "evidence_chapter_id"));
                edge.put("generationId", nullableLong(rs, "generation_id"));
                return edge;
            },
            params.toArray()
        );
        for (Map<String, Object> edge : edges) {
            Long edgeId = longValue(edge.get("edgeId"));
            if (edgeId != null) {
                edgesById.put(edgeId, edge);
            }
        }
        if (nodesById.size() >= limit) {
            gaps.add("node_limit_reached");
        }
        return graphResult(nodesById, edgesById, List.of(), gaps, !gaps.isEmpty());
    }

    private List<Map<String, Object>> findSeedNodes(Long userId, Long projectId, Long workId, Collection<String> rawTerms) {
        List<String> terms = normalizedTerms(rawTerms);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(List.of(userId, projectId, workId));
        List<String> predicates = new ArrayList<>();
        for (String term : terms) {
            if (term.length() <= 2) {
                predicates.add("(n.canonical_key = ? or n.display_name = ? or n.aliases = ? or n.aliases like ?)");
                args.add(term);
                args.add(term);
                args.add("|" + term + "|");
                args.add("|" + term + "%");
            } else {
                predicates.add("(n.canonical_key = ? or n.display_name = ? or n.aliases like ?)");
                args.add(term);
                args.add(term);
                args.add("|" + term + "%");
            }
        }
        args.add(10);
        String sql = """
            select n.node_id, n.generation_id, n.source_chapter_id, n.node_type, n.canonical_key,
                n.display_name, n.aliases, n.confidence
            from ai_project_story_node n
            join ai_project_ingest_generation g on g.generation_id = n.generation_id and g.status = 'ACTIVE'
            where n.user_id = ? and n.project_id = ? and n.work_id = ? and n.status = 'ACTIVE'
              and exists (
                select 1 from ai_project_chapter_head h
                where h.user_id = n.user_id and h.project_id = n.project_id and h.work_id = n.work_id
                  and h.active_generation_id = n.generation_id and h.tombstoned_at is null
              )
              and (""" + String.join(" or ", predicates) + ") order by n.node_id asc limit ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> nodeRow(rs), args.toArray());
    }

    private List<Map<String, Object>> findAdjacentEdges(Long userId, Long projectId, Long workId, List<Long> nodeIds, int limit) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        String placeholders = placeholders(nodeIds.size());
        List<Object> args = new ArrayList<>(List.of(userId, projectId, workId));
        args.addAll(nodeIds);
        args.addAll(nodeIds);
        args.add(limit);
        String sql = """
            select e.edge_id, e.generation_id, e.from_node_id, e.to_node_id, e.relation_type,
                e.relation_group, e.evidence_chapter_id, e.evidence_scene_id, e.evidence_ref,
                e.valid_from_chapter_no, e.valid_to_chapter_no, e.confidence
            from ai_project_story_edge e
            join ai_project_ingest_generation g on g.generation_id = e.generation_id and g.status = 'ACTIVE'
            where e.user_id = ? and e.project_id = ? and e.work_id = ? and e.status = 'ACTIVE'
              and e.evidence_chapter_id is not null
              and (e.from_node_id in (%s) or e.to_node_id in (%s))
              and exists (
                select 1 from ai_project_chapter_head h
                where h.user_id = e.user_id and h.project_id = e.project_id and h.work_id = e.work_id
                  and h.active_generation_id = e.generation_id and h.tombstoned_at is null
              )
            order by e.edge_id asc limit ?
            """.formatted(placeholders, placeholders);
        return jdbcTemplate.query(sql, (rs, rowNum) -> edgeRow(rs), args.toArray());
    }

    private List<Map<String, Object>> findVisibleNodesById(Long userId, Long projectId, Long workId, Collection<Long> nodeIds) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(List.of(userId, projectId, workId));
        args.addAll(nodeIds);
        String sql = """
            select n.node_id, n.generation_id, n.source_chapter_id, n.node_type, n.canonical_key,
                n.display_name, n.aliases, n.confidence
            from ai_project_story_node n
            join ai_project_ingest_generation g on g.generation_id = n.generation_id and g.status = 'ACTIVE'
            where n.user_id = ? and n.project_id = ? and n.work_id = ? and n.status = 'ACTIVE'
              and n.node_id in (%s)
              and exists (
                select 1 from ai_project_chapter_head h
                where h.user_id = n.user_id and h.project_id = n.project_id and h.work_id = n.work_id
                  and h.active_generation_id = n.generation_id and h.tombstoned_at is null
              ) order by n.node_id asc
            """.formatted(placeholders(nodeIds.size()));
        return jdbcTemplate.query(sql, (rs, rowNum) -> nodeRow(rs), args.toArray());
    }

    private void upsertSearchDocument(SearchDocumentInput input) {
        List<Long> existing = jdbcTemplate.query(
            "select document_id from ai_project_search_document where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and document_key = ?",
            (rs, rowNum) -> rs.getLong(1), input.userId(), input.projectId(), input.workId(), input.generationId(), input.documentKey()
        );
        if (!existing.isEmpty()) {
            updateSearchDocument(input, existing.get(0));
            return;
        }
        try {
            jdbcTemplate.update(
                "insert into ai_project_search_document(user_id, project_id, work_id, chapter_id, generation_id, chapter_version, scene_id, source_id, document_type, document_key, title, aliases, content, content_hash, confidence, status) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                input.userId(), input.projectId(), input.workId(), input.chapterId(), input.generationId(), input.chapterVersion(), input.sceneId(), input.sourceId(),
                input.documentType(), trim(input.documentKey(), 320), trim(input.title(), 500), aliasEnvelope(input.aliases()), input.content(), trim(input.contentHash(), 64), input.confidence()
            );
        } catch (DuplicateKeyException ex) {
            List<Long> concurrent = jdbcTemplate.query(
                "select document_id from ai_project_search_document where user_id = ? and project_id = ? and work_id = ? and generation_id = ? and document_key = ?",
                (rs, rowNum) -> rs.getLong(1), input.userId(), input.projectId(), input.workId(), input.generationId(), input.documentKey()
            );
            if (concurrent.isEmpty()) {
                throw ex;
            }
            updateSearchDocument(input, concurrent.get(0));
        }
    }

    private void updateSearchDocument(SearchDocumentInput input, Long documentId) {
        jdbcTemplate.update(
            "update ai_project_search_document set chapter_id = ?, chapter_version = ?, scene_id = ?, source_id = ?, document_type = ?, title = ?, aliases = ?, content = ?, content_hash = ?, confidence = ?, status = 'ACTIVE', updated_at = current_timestamp where document_id = ?",
            input.chapterId(), input.chapterVersion(), input.sceneId(), input.sourceId(), input.documentType(), trim(input.title(), 500),
            aliasEnvelope(input.aliases()), input.content(), trim(input.contentHash(), 64), input.confidence(), documentId
        );
    }

    private void requireNodeInScope(Long nodeId, EdgeInput input) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_story_node where node_id = ? and user_id = ? and project_id = ? and work_id = ? and generation_id = ? and status = 'ACTIVE'",
            Integer.class, nodeId, input.userId(), input.projectId(), input.workId(), input.generationId()
        );
        if (count == null || count != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "story edge node scope mismatch");
        }
    }

    private void requireNodeInput(NodeInput input) {
        if (input == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "story node is required");
        }
        requireScope(input.userId(), input.projectId(), input.workId());
        if (input.generationId() == null || input.sourceChapterId() == null || blank(input.nodeType()) || blank(input.canonicalKey()) || blank(input.displayName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "story node scope and identity are required");
        }
    }

    private void requireEdgeInput(EdgeInput input) {
        if (input == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "story edge is required");
        }
        requireScope(input.userId(), input.projectId(), input.workId());
        if (input.generationId() == null || input.fromNodeId() == null || input.toNodeId() == null
            || blank(input.relationType()) || blank(input.relationGroup()) || input.evidenceChapterId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "story edge identity and chapter evidence are required");
        }
    }

    private void requireScope(Long userId, Long projectId, Long workId) {
        if (userId == null || projectId == null || workId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project retrieval scope is required");
        }
    }

    private StoryGraphResultVO graphResult(Map<Long, Map<String, Object>> nodesById,
                                           Map<Long, Map<String, Object>> edgesById,
                                           List<Map<String, Object>> paths,
                                           Set<String> gaps,
                                           boolean partial) {
        StoryGraphResultVO result = new StoryGraphResultVO();
        result.setNodes(new ArrayList<>(nodesById.values()));
        result.setEdges(new ArrayList<>(edgesById.values()));
        result.setPaths(paths);
        result.setGaps(new ArrayList<>(gaps));
        result.setPartial(partial || !gaps.isEmpty());
        return result;
    }

    private Long parentForNeighbor(List<Long> frontier, List<Map<String, Object>> edges, Long neighborId) {
        for (Map<String, Object> edge : edges) {
            Long from = longValue(edge.get("fromNodeId"));
            Long to = longValue(edge.get("toNodeId"));
            if (to != null && to.equals(neighborId) && frontier.contains(from)) {
                return from;
            }
            if (from != null && from.equals(neighborId) && frontier.contains(to)) {
                return to;
            }
        }
        return null;
    }

    private Map<String, Object> nodeRow(ResultSet rs) throws SQLException {
        return row(
            "nodeId", rs.getLong("node_id"), "generationId", rs.getLong("generation_id"),
            "sourceChapterId", rs.getLong("source_chapter_id"), "nodeType", rs.getString("node_type"),
            "canonicalKey", rs.getString("canonical_key"), "displayName", rs.getString("display_name"),
            "aliases", rs.getString("aliases"), "confidence", decimal(rs, "confidence")
        );
    }

    private Map<String, Object> edgeRow(ResultSet rs) throws SQLException {
        return row(
            "edgeId", rs.getLong("edge_id"), "generationId", rs.getLong("generation_id"),
            "fromNodeId", rs.getLong("from_node_id"), "toNodeId", rs.getLong("to_node_id"),
            "relationType", rs.getString("relation_type"), "relationGroup", rs.getString("relation_group"),
            "evidenceChapterId", nullableLong(rs, "evidence_chapter_id"), "evidenceSceneId", nullableLong(rs, "evidence_scene_id"),
            "evidenceRef", rs.getString("evidence_ref"), "validFromChapterNo", nullableInt(rs, "valid_from_chapter_no"),
            "validToChapterNo", nullableInt(rs, "valid_to_chapter_no"), "confidence", decimal(rs, "confidence")
        );
    }

    private Map<String, Object> path(List<Long> nodeIds) {
        return row("nodeIds", nodeIds, "hopCount", Math.max(0, nodeIds.size() - 1));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }

    private List<String> normalizedTerms(Collection<String> rawTerms) {
        if (rawTerms == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String term : rawTerms) {
            String normalized = canonicalKey(term);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
            if (values.size() >= 8) {
                break;
            }
        }
        return new ArrayList<>(values);
    }

    private String canonicalKey(String value) {
        String normalized = trim(value, 240);
        if (normalized == null) {
            return "";
        }
        return SPACE.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll("-");
    }

    private String edgeKey(EdgeInput input, Long fromNodeId, Long toNodeId) {
        return trim(
            input.relationType() + ":" + fromNodeId + ":" + toNodeId + ":" + input.evidenceChapterId() + ":" + input.evidenceSceneId(),
            320
        );
    }

    private String aliasEnvelope(Collection<String> aliases) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (aliases != null) {
            for (String alias : aliases) {
                String value = trim(alias, 120);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return trim("|" + String.join("|", values) + "|", 1000);
    }

    private List<String> aliases(String value) {
        String normalized = trim(value, 120);
        return normalized == null ? List.of() : List.of(normalized);
    }

    private String titleForChapter(ProjectChapterVO chapter) {
        String title = trim(chapter.getTitle(), 500);
        return title == null ? "Chapter " + chapter.getChapterNo() : title;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "sha-256 unavailable");
        }
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private String trim(String value, int limit) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private boolean blank(String value) { return trim(value, 1) == null; }
    private Long longValue(Object value) { return value instanceof Number number ? number.longValue() : value == null ? null : Long.valueOf(String.valueOf(value)); }
    private Integer nullableInt(Object value) { return value instanceof Number number ? number.intValue() : value == null ? null : Integer.valueOf(String.valueOf(value)); }
    private Long nullableLong(ResultSet rs, String column) throws SQLException { Object value = rs.getObject(column); return value == null ? null : ((Number) value).longValue(); }
    private Integer nullableInt(ResultSet rs, String column) throws SQLException { Object value = rs.getObject(column); return value == null ? null : ((Number) value).intValue(); }
    private Double decimal(ResultSet rs, String column) throws SQLException { Object value = rs.getObject(column); return value instanceof Number number ? number.doubleValue() : null; }
    private Double doubleValue(Object value) { return value instanceof Number number ? number.doubleValue() : null; }
    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    public record NodeInput(Long userId, Long projectId, Long workId, Long generationId, Long sourceChapterId,
                            String nodeType, String canonicalKey, String displayName, Collection<String> aliases,
                            Double confidence) { }

    public record EdgeInput(Long userId, Long projectId, Long workId, Long generationId, Long fromNodeId,
                            Long toNodeId, String relationType, String relationGroup, Long evidenceChapterId,
                            Long evidenceSceneId, String evidenceRef, Integer validFromChapterNo,
                            Integer validToChapterNo, Double confidence, boolean symmetric) { }

    private record SearchDocumentInput(Long userId, Long projectId, Long workId, Long chapterId, Long generationId,
                                       Integer chapterVersion, Long sceneId, Long sourceId, String documentType,
                                       String documentKey, String title, Collection<String> aliases, String content,
                                       String contentHash, Double confidence) { }

    public record IndexCounts(int documentCount, int nodeCount) { }
}

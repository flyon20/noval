package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.GoldenCandidateDraftVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTraceVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTracePageVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeAgentTraceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeAgentTraceService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper());
    }

    @Autowired
    public KnowledgeAgentTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void persistFromChat(Long userId,
                                Long projectId,
                                String conversationId,
                                String question,
                                KnowledgeChatResponseVO response) {
        if (userId == null || response == null || response.getResultJson() == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        if (!resultJson.containsKey("taskGraph") && !resultJson.containsKey("toolRuns")) {
            return;
        }
        if (!resultJson.containsKey("answer") && trimToNull(response.getAnswer()) != null) {
            resultJson.put("answer", response.getAnswer());
        }
        String traceId = resolveTraceId(resultJson);
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, project_id, conversation_id, question, status, " +
                "task_graph_json, tool_runs_json, evidence_pack_json, perspective_results_json, result_json) " +
                "values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            traceId,
            userId,
            projectId,
            conversationId,
            question,
            response.getStatus(),
            toJson(resultJson.get("taskGraph")),
            toJson(resultJson.get("toolRuns")),
            toJson(resultJson.get("evidencePackSummary")),
            toJson(resultJson.get("perspectiveResults")),
            toJson(resultJson)
        );
    }

    public List<KnowledgeAgentTraceVO> listForAdmin() {
        requireAdmin();
        return jdbcTemplate.query(
            "select id, trace_id, user_id, project_id, conversation_id, question, status, task_graph_json, " +
                "tool_runs_json, evidence_pack_json, perspective_results_json, result_json, created_at " +
                "from ai_agent_trace order by id desc limit 100",
            mapper()
        );
    }

    public KnowledgeAgentTracePageVO listForAdmin(Integer page, Integer pageSize, String status, String keyword) {
        requireAdmin();
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);
        long offset = (long) (safePage - 1) * safePageSize;
        List<Object> args = new ArrayList<>();
        String where = buildWhere(status, keyword, args);
        Long total = jdbcTemplate.queryForObject(
            "select count(*) from ai_agent_trace" + where,
            Long.class,
            args.toArray()
        );
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<KnowledgeAgentTraceVO> items = jdbcTemplate.query(
            "select id, trace_id, user_id, project_id, conversation_id, question, status, result_json, created_at " +
                "from ai_agent_trace" + where + " order by id desc limit ? offset ?",
            summaryMapper(),
            pageArgs.toArray()
        );
        KnowledgeAgentTracePageVO vo = new KnowledgeAgentTracePageVO();
        vo.setPage(safePage);
        vo.setPageSize(safePageSize);
        vo.setTotal(total == null ? 0L : total);
        vo.setHasNext((long) safePage * safePageSize < vo.getTotal());
        vo.setItems(items);
        return vo;
    }

    public KnowledgeAgentTraceVO detailForAdmin(Long id) {
        requireAdmin();
        List<KnowledgeAgentTraceVO> traces = jdbcTemplate.query(
            "select id, trace_id, user_id, project_id, conversation_id, question, status, task_graph_json, " +
                "tool_runs_json, evidence_pack_json, perspective_results_json, result_json, created_at " +
                "from ai_agent_trace where id = ?",
            mapper(),
            id
        );
        if (traces.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "trace not found");
        }
        return traces.get(0);
    }

    public GoldenCandidateDraftVO createGoldenCandidateDraft(Long traceRecordId) {
        KnowledgeAgentTraceVO trace = detailForAdmin(traceRecordId);
        Map<String, Object> result = parseResultJson(trace.getResultJson());
        GoldenCandidateDraftVO draft = new GoldenCandidateDraftVO();
        draft.setTraceRecordId(trace.getId());
        draft.setTraceId(trace.getTraceId());
        draft.setQuestion(trace.getQuestion());
        draft.setAnswer(firstStringValue(result, "answer"));
        draft.setTraceSummary(traceSummary(result));
        draft.setSelectedSkills(extractSelectedSkills(result));
        draft.setSelectedTools(extractSelectedTools(result));
        draft.setEvidenceContract(toJsonOrNull(firstPresent(result, "evidenceContract", "evidencePackSummary")));
        draft.setStatus("DRAFT");
        return draft;
    }

    private String resolveTraceId(Map<String, Object> resultJson) {
        String direct = firstTraceId(resultJson, "traceId", "trace_id");
        if (direct != null) {
            return direct;
        }
        Object trace = resultJson.get("trace");
        if (trace instanceof Map<?, ?> map) {
            String traceId = firstTraceId(map, "traceId", "trace_id");
            if (traceId != null) {
                return traceId;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String firstTraceId(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            String traceId = trimToNull(value == null ? null : String.valueOf(value));
            if (traceId != null) {
                return traceId;
            }
        }
        return null;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "trace json serialization failed");
        }
    }

    private RowMapper<KnowledgeAgentTraceVO> mapper() {
        return (rs, rowNum) -> {
            KnowledgeAgentTraceVO vo = new KnowledgeAgentTraceVO();
            vo.setId(rs.getLong("id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setUserId(rs.getLong("user_id"));
            long projectId = rs.getLong("project_id");
            vo.setProjectId(rs.wasNull() ? null : projectId);
            vo.setConversationId(rs.getString("conversation_id"));
            vo.setQuestion(rs.getString("question"));
            vo.setStatus(rs.getString("status"));
            vo.setTaskGraph(rs.getString("task_graph_json"));
            vo.setToolRuns(rs.getString("tool_runs_json"));
            vo.setEvidencePack(rs.getString("evidence_pack_json"));
            vo.setPerspectiveResults(rs.getString("perspective_results_json"));
            String resultJson = rs.getString("result_json");
            vo.setResultJson(resultJson);
            hydrateExpandedTraceSections(vo, resultJson);
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return vo;
        };
    }

    private RowMapper<KnowledgeAgentTraceVO> summaryMapper() {
        return (rs, rowNum) -> {
            KnowledgeAgentTraceVO vo = new KnowledgeAgentTraceVO();
            vo.setId(rs.getLong("id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setUserId(rs.getLong("user_id"));
            long projectId = rs.getLong("project_id");
            vo.setProjectId(rs.wasNull() ? null : projectId);
            vo.setConversationId(rs.getString("conversation_id"));
            vo.setQuestion(rs.getString("question"));
            vo.setStatus(rs.getString("status"));
            vo.setResultJson(rs.getString("result_json"));
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return vo;
        };
    }

    private String buildWhere(String status, String keyword, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        String normalizedStatus = trimToNull(status);
        if (normalizedStatus != null) {
            clauses.add("status = ?");
            args.add(normalizedStatus);
        }
        String normalizedKeyword = trimToNull(keyword);
        if (normalizedKeyword != null) {
            clauses.add("(trace_id like ? or question like ? or conversation_id like ?)");
            String like = "%" + normalizedKeyword + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private int normalizePage(Integer page) {
        if (page == null || page <= 0) {
            return 1;
        }
        return Math.min(page, 10000);
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 50);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void hydrateExpandedTraceSections(KnowledgeAgentTraceVO vo, String resultJson) {
        Map<String, Object> result = parseResultJson(resultJson);
        if (result.isEmpty()) {
            return;
        }
        vo.setIntentDecision(toJsonOrNull(result.get("intentDecision")));
        vo.setContextUsed(toJsonOrNull(result.get("contextUsed")));
        vo.setMemoryUsed(toJsonOrNull(result.get("memoryUsed")));
        vo.setMemoryDiagnostics(toJsonOrNull(result.get("memoryDiagnostics")));
        vo.setRetrievalDiagnostics(toJsonOrNull(result.get("retrievalDiagnostics")));
        vo.setSourcePolicy(toJsonOrNull(result.get("sourcePolicy")));
        vo.setSupervisorDecision(toJsonOrNull(result.get("supervisorDecision")));
        vo.setMemoryCandidates(toJsonOrNull(result.get("memoryCandidates")));
        vo.setMcpToolCalls(toJsonOrNull(firstPresent(result, "mcpToolCalls", "toolRuns")));
        vo.setToolPermissionDecisions(toJsonOrNull(result.get("toolPermissionDecisions")));
        vo.setEvidenceContract(toJsonOrNull(result.get("evidenceContract")));
        vo.setSelectedSnapshotGroup(toJsonOrNull(result.get("selectedSnapshotGroup")));
        vo.setRejectedSnapshotGroups(toJsonOrNull(result.get("rejectedSnapshotGroups")));
        vo.setSpecialistAgentResults(toJsonOrNull(result.get("specialistAgentResults")));
        vo.setSelectedExperts(toJsonOrNull(result.get("selectedExperts")));
        vo.setExpertRouter(toJsonOrNull(result.get("expertRouter")));
        vo.setFinalAnswerBoundary(toJsonOrNull(result.get("finalAnswerBoundary")));
        vo.setSnapshotTime(firstStringValue(result.get("sourcePolicy"), "snapshotTime"));
        if (vo.getSnapshotTime() == null) {
            vo.setSnapshotTime(firstStringValue(result, "snapshotTime"));
        }
    }

    private Map<String, Object> parseResultJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(resultJson, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJsonOrNull(Object value) {
        return value == null ? null : toJson(value);
    }

    private String traceSummary(Map<String, Object> result) {
        String supervisor = firstStringValue(result.get("supervisorDecision"), "summary");
        String boundary = firstStringValue(result.get("finalAnswerBoundary"), "summary");
        List<String> parts = new ArrayList<>();
        if (supervisor != null) {
            parts.add(supervisor);
        }
        if (boundary != null) {
            parts.add(boundary);
        }
        if (!parts.isEmpty()) {
            return String.join("; ", parts);
        }
        String status = firstStringValue(result.get("supervisorDecision"), "status");
        if (status != null) {
            return "supervisorDecision=" + status;
        }
        Object taskGraph = result.get("taskGraph");
        return taskGraph == null ? null : toJsonOrNull(taskGraph);
    }

    private List<String> extractSelectedSkills(Map<String, Object> result) {
        Set<String> skills = new LinkedHashSet<>();
        collectStringValues(result.get("selectedSkills"), skills, "skillId", "name");
        collectStringValues(result.get("selectedExperts"), skills, "skillId", "name");
        Object expertRouter = result.get("expertRouter");
        if (expertRouter instanceof Map<?, ?> router) {
            collectStringValues(router.get("selectedExperts"), skills, "skillId", "name");
        }
        return new ArrayList<>(skills);
    }

    private List<String> extractSelectedTools(Map<String, Object> result) {
        Set<String> tools = new LinkedHashSet<>();
        collectStringValues(firstPresent(result, "mcpToolCalls", "toolRuns"), tools, "name", "tool", "toolName");
        return new ArrayList<>(tools);
    }

    private void collectStringValues(Object value, Set<String> target, String... objectKeys) {
        if (value instanceof String text && !text.isBlank()) {
            target.add(text);
        } else if (value instanceof Map<?, ?> map) {
            for (String key : objectKeys) {
                Object direct = map.get(key);
                if (direct != null && !String.valueOf(direct).isBlank()) {
                    target.add(String.valueOf(direct));
                    return;
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectStringValues(item, target, objectKeys);
            }
        }
    }

    private String firstStringValue(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            Object direct = map.get(key);
            if (direct != null && !String.valueOf(direct).isBlank()) {
                return String.valueOf(direct);
            }
            for (Object child : map.values()) {
                String found = firstStringValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                String found = firstStringValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private void requireAdmin() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || !user.getRoles().contains("ADMIN")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin role required");
        }
    }
}

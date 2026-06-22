package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeAgentTraceVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
        Map<String, Object> resultJson = response.getResultJson();
        if (!resultJson.containsKey("taskGraph") && !resultJson.containsKey("toolRuns")) {
            return;
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

    private String resolveTraceId(Map<String, Object> resultJson) {
        Object trace = resultJson.get("trace");
        if (trace instanceof Map<?, ?> map) {
            Object traceId = map.get("traceId");
            if (traceId != null && !String.valueOf(traceId).isBlank()) {
                return String.valueOf(traceId);
            }
        }
        return UUID.randomUUID().toString();
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

    private void hydrateExpandedTraceSections(KnowledgeAgentTraceVO vo, String resultJson) {
        Map<String, Object> result = parseResultJson(resultJson);
        if (result.isEmpty()) {
            return;
        }
        vo.setIntentDecision(toJsonOrNull(result.get("intentDecision")));
        vo.setContextUsed(toJsonOrNull(result.get("contextUsed")));
        vo.setMemoryUsed(toJsonOrNull(result.get("memoryUsed")));
        vo.setSourcePolicy(toJsonOrNull(result.get("sourcePolicy")));
        vo.setSupervisorDecision(toJsonOrNull(result.get("supervisorDecision")));
        vo.setMemoryCandidates(toJsonOrNull(result.get("memoryCandidates")));
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

    private void requireAdmin() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || !user.getRoles().contains("ADMIN")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin role required");
        }
    }
}

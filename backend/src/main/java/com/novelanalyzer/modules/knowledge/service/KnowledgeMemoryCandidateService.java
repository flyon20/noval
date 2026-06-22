package com.novelanalyzer.modules.knowledge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeMemoryCandidateService {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeMemoryCandidateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int persistCandidates(Long projectId,
                                 Long userId,
                                 List<Map<String, Object>> candidates,
                                 String fallbackTraceId) {
        if (projectId == null || userId == null || candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (Map<String, Object> candidate : candidates) {
            if (candidate == null || "discard".equals(trimToNull(stringValue(candidate.get("scope"))))) {
                continue;
            }
            String scope = trimToNull(stringValue(candidate.get("scope")));
            String type = trimToNull(stringValue(candidate.get("type")));
            String content = trimToNull(stringValue(candidate.get("content")));
            if (scope == null || type == null || content == null) {
                continue;
            }
            String sourceTraceId = trimToNull(stringValue(candidate.get("sourceTraceId")));
            if (sourceTraceId == null) {
                sourceTraceId = trimToNull(fallbackTraceId);
            }
            jdbcTemplate.update(
                "insert into ai_memory_candidate(project_id, user_id, candidate_type, content, status, source_trace_id) values(?, ?, ?, ?, ?, ?)",
                projectId,
                userId,
                scope + "." + type,
                content,
                statusFor(scope, confidence(candidate.get("confidence"))),
                sourceTraceId
            );
            saved++;
        }
        return saved;
    }

    private String statusFor(String scope, double confidence) {
        if ("project".equals(scope) && confidence >= 0.8d) {
            return "APPROVED";
        }
        return "PENDING";
    }

    private double confidence(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

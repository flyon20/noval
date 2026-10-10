package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.knowledge.dto.AiMemoryCandidateRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeMemoryCandidateService {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeMemoryService knowledgeMemoryService;

    public KnowledgeMemoryCandidateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeMemoryService = new KnowledgeMemoryService(jdbcTemplate);
    }

    public int persistCandidates(Long projectId,
                                 Long userId,
                                 List<Map<String, Object>> candidates,
                                 String fallbackTraceId) {
        if (userId == null || candidates == null || candidates.isEmpty()) {
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
            if ("project".equals(scope) && projectId == null) {
                continue;
            }
            String sourceTraceId = trimToNull(stringValue(candidate.get("sourceTraceId")));
            if (sourceTraceId == null) {
                sourceTraceId = trimToNull(fallbackTraceId);
            }
            AiMemoryCandidateRequest request = new AiMemoryCandidateRequest();
            request.setUserId(userId);
            request.setProjectId(projectId);
            request.setConversationId(trimToNull(stringValue(candidate.get("conversationId"))));
            request.setScope(scope);
            request.setMemoryType(type);
            request.setContent(content);
            request.setSummary(trimToNull(stringValue(candidate.get("summary"))));
            request.setConfidence(confidence(candidate.get("confidence")));
            request.setSourceTraceId(sourceTraceId);
            request.setFactKey(trimToNull(stringValue(candidate.get("factKey"))));
            request.setCandidateKey(trimToNull(stringValue(candidate.get("candidateKey"))));
            request.setProvenanceJson(trimToNull(stringValue(candidate.get("provenanceJson"))));
            request.setEvidenceJson(trimToNull(stringValue(candidate.get("evidenceJson"))));
            request.setSourceEvidenceIdsJson(trimToNull(stringValue(candidate.get("sourceEvidenceIdsJson"))));
            request.setSourceChapterVersionsJson(trimToNull(stringValue(candidate.get("sourceChapterVersionsJson"))));
            request.setIndexGeneration(trimToNull(stringValue(candidate.get("indexGeneration"))));
            request.setExtractorVersion(trimToNull(stringValue(candidate.get("extractorVersion"))));
            request.setSupersedesId(longValue(candidate.get("supersedesId")));
            request.setTtlDays(ttlDays(candidate.get("ttlDays")));
            knowledgeMemoryService.createCandidate(request);
            saved++;
        }
        return saved;
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

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int ttlDays(Object value) {
        Long parsed = longValue(value);
        if (parsed == null) return 30;
        return (int) Math.max(1L, Math.min(365L, parsed));
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

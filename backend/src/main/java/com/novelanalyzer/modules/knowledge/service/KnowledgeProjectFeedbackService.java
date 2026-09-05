package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.dto.ProjectKnowledgeFeedbackRequest;
import com.novelanalyzer.modules.knowledge.vo.ProjectKnowledgeFeedbackVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class KnowledgeProjectFeedbackService {
    public static final String STATUS_PENDING = "PENDING";
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "RECALL_ERROR",
        "CONFIRM_FORESHADOWING",
        "DENY_FORESHADOWING",
        "MERGE_ALIAS",
        "FIX_TIMELINE",
        "SAVE_PROJECT_DECISION"
    );

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectService knowledgeProjectService;

    public KnowledgeProjectFeedbackService(JdbcTemplate jdbcTemplate,
                                           KnowledgeProjectService knowledgeProjectService) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeProjectService = knowledgeProjectService;
    }

    public ProjectKnowledgeFeedbackVO submit(Long projectId, ProjectKnowledgeFeedbackRequest request) {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "login required");
        }
        if (projectId == null || projectId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "projectId required");
        }
        knowledgeProjectService.ensureOwned(projectId, user.getUserId());
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "request required");
        }
        String feedbackType = normalizeToken(request.getFeedbackType());
        if (feedbackType == null || !ALLOWED_TYPES.contains(feedbackType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported feedbackType");
        }
        String targetType = normalizeToken(request.getTargetType());
        String targetKey = trimToNull(request.getTargetKey());
        if (targetType == null || targetKey == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "targetType and targetKey required");
        }
        if (containsConfirmedMemoryPromotion(request)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "feedback cannot promote confirmed memory");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into ai_project_knowledge_feedback(" +
                    "user_id, project_id, work_id, generation_id, conversation_id, trace_id, " +
                    "feedback_type, target_type, target_key, old_value_json, new_value_json, " +
                    "evidence_json, operator_user_id, review_status, notes, deleted" +
                ") values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                new String[]{"feedback_id"}
            );
            ps.setLong(1, user.getUserId());
            ps.setLong(2, projectId);
            if (request.getWorkId() == null) {
                ps.setObject(3, null);
            } else {
                ps.setLong(3, request.getWorkId());
            }
            if (request.getGenerationId() == null) {
                ps.setObject(4, null);
            } else {
                ps.setLong(4, request.getGenerationId());
            }
            ps.setString(5, trimToNull(request.getConversationId()));
            ps.setString(6, trimToNull(request.getTraceId()));
            ps.setString(7, feedbackType);
            ps.setString(8, targetType);
            ps.setString(9, targetKey);
            ps.setString(10, request.getOldValueJson());
            ps.setString(11, request.getNewValueJson());
            ps.setString(12, request.getEvidenceJson());
            ps.setLong(13, user.getUserId());
            ps.setString(14, STATUS_PENDING);
            ps.setString(15, trimToNull(request.getNotes()));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "feedback id missing");
        }
        ProjectKnowledgeFeedbackVO vo = new ProjectKnowledgeFeedbackVO();
        vo.setFeedbackId(key.longValue());
        vo.setProjectId(projectId);
        vo.setWorkId(request.getWorkId());
        vo.setGenerationId(request.getGenerationId());
        vo.setFeedbackType(feedbackType);
        vo.setTargetType(targetType);
        vo.setTargetKey(targetKey);
        vo.setReviewStatus(STATUS_PENDING);
        vo.setOperatorUserId(user.getUserId());
        return vo;
    }

    private boolean containsConfirmedMemoryPromotion(ProjectKnowledgeFeedbackRequest request) {
        String joined = String.join(" ",
            nullToEmpty(request.getOldValueJson()),
            nullToEmpty(request.getNewValueJson()),
            nullToEmpty(request.getNotes()),
            nullToEmpty(request.getTargetType()),
            nullToEmpty(request.getFeedbackType())
        ).toUpperCase(Locale.ROOT);
        return joined.contains("CONFIRMED") && (joined.contains("MEMORY") || joined.contains("FACT"));
    }

    private String normalizeToken(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

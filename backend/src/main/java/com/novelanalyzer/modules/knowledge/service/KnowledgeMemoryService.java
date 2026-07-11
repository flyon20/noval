package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.AiMemoryVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeMemoryService {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeMemoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long createCandidate(Long userId,
                                Long projectId,
                                String conversationId,
                                String scope,
                                String memoryType,
                                String content,
                                String summary,
                                double confidence,
                                String sourceTraceId,
                                int ttlDays) {
        requireUser(userId);
        String normalizedScope = requireText(scope, "memory scope is required");
        String normalizedType = requireText(memoryType, "memory type is required");
        String normalizedContent = requireText(content, "memory content is required");
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(ttlDays);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                    insert into ai_memory_candidate(
                        project_id, user_id, conversation_id, scope, memory_type, candidate_type,
                        content, summary, confidence, status, source_trace_id, expires_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setObject(1, projectId);
            ps.setLong(2, userId);
            ps.setString(3, trimToNull(conversationId));
            ps.setString(4, normalizedScope);
            ps.setString(5, normalizedType);
            ps.setString(6, normalizedScope + "." + normalizedType);
            ps.setString(7, normalizedContent);
            ps.setString(8, trimToNull(summary));
            ps.setDouble(9, confidence);
            ps.setString(10, "candidate");
            ps.setString(11, trimToNull(sourceTraceId));
            ps.setTimestamp(12, Timestamp.valueOf(expiresAt));
            return ps;
        }, keyHolder);
        return generatedId(keyHolder, "memory candidate id missing");
    }

    public AiMemoryVO promoteCandidate(Long candidateId, Long userId) {
        requireUser(userId);
        AiMemoryVO candidate = findCandidate(candidateId, userId);
        Long memoryId = insertConfirmedMemory(candidate);
        jdbcTemplate.update(
            "update ai_memory_candidate set status = 'confirmed', updated_at = current_timestamp where id = ? and user_id = ?",
            candidateId,
            userId
        );
        candidate.setId(memoryId);
        candidate.setStatus("confirmed");
        return candidate;
    }

    public void rejectCandidate(Long candidateId, Long userId) {
        requireUser(userId);
        jdbcTemplate.update(
            "update ai_memory_candidate set status = 'rejected', updated_at = current_timestamp where id = ? and user_id = ?",
            candidateId,
            userId
        );
    }

    public int expireCandidates() {
        return jdbcTemplate.update(
            """
                update ai_memory_candidate
                set status = 'expired', updated_at = current_timestamp
                where status = 'candidate' and expires_at is not null and expires_at < current_timestamp
                """
        );
    }

    public List<AiMemoryVO> searchConfirmedMemory(Long userId, Long projectId, String scope, int limit) {
        requireUser(userId);
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
            """
                select id, user_id, project_id, conversation_id, scope, memory_type, content,
                       summary, confidence, status, source_trace_id
                from ai_memory_item
                where user_id = ?
                  and (? is null or project_id = ?)
                  and (? is null or scope = ?)
                  and status = 'confirmed'
                  and deleted_at is null
                order by updated_at desc, id desc
                limit ?
                """,
            (rs, rowNum) -> mapMemory(rs.getLong("id"), rs.getLong("user_id"),
                (Long) rs.getObject("project_id"), rs.getString("conversation_id"),
                rs.getString("scope"), rs.getString("memory_type"), rs.getString("content"),
                rs.getString("summary"), rs.getDouble("confidence"), rs.getString("status"),
                rs.getString("source_trace_id")),
            userId,
            projectId,
            projectId,
            trimToNull(scope),
            trimToNull(scope),
                effectiveLimit
        );
    }

    public List<AiMemoryVO> listMemoriesForAdmin(Long userId, Long projectId, String status, String scope, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
            """
                select id, user_id, project_id, conversation_id, scope, memory_type, content,
                       summary, confidence, status, source_trace_id
                from ai_memory_item
                where (? is null or user_id = ?)
                  and (? is null or project_id = ?)
                  and (? is null or status = ?)
                  and (? is null or scope = ?)
                  and deleted_at is null
                order by updated_at desc, id desc
                limit ?
                """,
            (rs, rowNum) -> mapMemory(rs.getLong("id"), rs.getLong("user_id"),
                (Long) rs.getObject("project_id"), rs.getString("conversation_id"),
                rs.getString("scope"), rs.getString("memory_type"), rs.getString("content"),
                rs.getString("summary"), rs.getDouble("confidence"), rs.getString("status"),
                rs.getString("source_trace_id")),
            userId, userId, projectId, projectId, trimToNull(status), trimToNull(status), trimToNull(scope), trimToNull(scope), effectiveLimit
        );
    }

    public List<AiMemoryVO> listCandidateMemoriesForAdmin(Long userId, Long projectId, String status, String scope, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
            """
                select id, user_id, project_id, conversation_id, scope, memory_type, content,
                       summary, confidence, status, source_trace_id
                from ai_memory_candidate
                where (? is null or user_id = ?)
                  and (? is null or project_id = ?)
                  and (? is null or status = ?)
                  and (? is null or scope = ?)
                order by updated_at desc, id desc
                limit ?
                """,
            (rs, rowNum) -> mapMemory(rs.getLong("id"), rs.getLong("user_id"),
                (Long) rs.getObject("project_id"), rs.getString("conversation_id"),
                rs.getString("scope"), rs.getString("memory_type"), rs.getString("content"),
                rs.getString("summary"), rs.getDouble("confidence"), rs.getString("status"),
                rs.getString("source_trace_id")),
            userId, userId, projectId, projectId, trimToNull(status), trimToNull(status), trimToNull(scope), trimToNull(scope), effectiveLimit
        );
    }

    public AiMemoryVO reviewCandidateForAdmin(Long candidateId, String decision) {
        String normalizedDecision = requireText(decision, "decision is required").toUpperCase();
        AiMemoryVO candidate = findCandidate(candidateId, null);
        if ("APPROVED".equals(normalizedDecision)) {
            Long memoryId = insertConfirmedMemory(candidate);
            jdbcTemplate.update(
                "update ai_memory_candidate set status = 'confirmed', updated_at = current_timestamp where id = ?",
                candidateId
            );
            candidate.setId(memoryId);
            candidate.setStatus("confirmed");
            return candidate;
        }
        if ("REJECTED".equals(normalizedDecision)) {
            jdbcTemplate.update(
                "update ai_memory_candidate set status = 'rejected', updated_at = current_timestamp where id = ?",
                candidateId
            );
            candidate.setStatus("rejected");
            return candidate;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "decision must be APPROVED or REJECTED");
    }

    public void deleteMemoryForAdmin(Long memoryId) {
        if (memoryId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "memoryId is required");
        }
        jdbcTemplate.update(
            "update ai_memory_item set status = 'deleted', deleted_at = current_timestamp, updated_at = current_timestamp where id = ?",
            memoryId
        );
    }

    private Long insertConfirmedMemory(AiMemoryVO candidate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                    insert into ai_memory_item(
                        user_id, project_id, conversation_id, scope, memory_type, content,
                        summary, confidence, status, source_trace_id
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, candidate.getUserId());
            ps.setObject(2, candidate.getProjectId());
            ps.setString(3, candidate.getConversationId());
            ps.setString(4, candidate.getScope());
            ps.setString(5, candidate.getMemoryType());
            ps.setString(6, candidate.getContent());
            ps.setString(7, candidate.getSummary());
            ps.setObject(8, candidate.getConfidence());
            ps.setString(9, "confirmed");
            ps.setString(10, candidate.getSourceTraceId());
            return ps;
        }, keyHolder);
        return generatedId(keyHolder, "memory id missing");
    }

    private Long generatedId(KeyHolder keyHolder, String errorMessage) {
        List<Map<String, Object>> keyList = keyHolder.getKeyList();
        if (!keyList.isEmpty()) {
            Map<String, Object> keys = keyList.get(0);
            Object id = keys.get("id");
            if (id instanceof Number number) {
                return number.longValue();
            }
            if (keys.size() == 1) {
                Object value = keys.values().iterator().next();
                if (value instanceof Number number) {
                    return number.longValue();
                }
            }
        }
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, errorMessage);
    }

    private AiMemoryVO findCandidate(Long candidateId, Long userId) {
        List<AiMemoryVO> candidates = jdbcTemplate.query(
            """
                select id, user_id, project_id, conversation_id, scope, memory_type, content,
                       summary, confidence, status, source_trace_id
                from ai_memory_candidate
                where id = ? and (? is null or user_id = ?) and status = 'candidate'
                """,
            (rs, rowNum) -> mapMemory(rs.getLong("id"), rs.getLong("user_id"),
                (Long) rs.getObject("project_id"), rs.getString("conversation_id"),
                rs.getString("scope"), rs.getString("memory_type"), rs.getString("content"),
                rs.getString("summary"), rs.getDouble("confidence"), rs.getString("status"),
                rs.getString("source_trace_id")),
            candidateId,
            userId,
            userId
        );
        if (candidates.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "memory candidate not found");
        }
        return candidates.get(0);
    }

    private AiMemoryVO mapMemory(Long id,
                                 Long userId,
                                 Long projectId,
                                 String conversationId,
                                 String scope,
                                 String memoryType,
                                 String content,
                                 String summary,
                                 Double confidence,
                                 String status,
                                 String sourceTraceId) {
        AiMemoryVO vo = new AiMemoryVO();
        vo.setId(id);
        vo.setUserId(userId);
        vo.setProjectId(projectId);
        vo.setConversationId(conversationId);
        vo.setScope(scope);
        vo.setMemoryType(memoryType);
        vo.setContent(content);
        vo.setSummary(summary);
        vo.setConfidence(confidence);
        vo.setStatus(status);
        vo.setSourceTraceId(sourceTraceId);
        return vo;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "userId is required");
        }
    }

    private String requireText(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

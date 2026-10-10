package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatMessageVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeConversationVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeConversationService {

    private static final Set<String> MESSAGE_ROLES = Set.of("USER", "ASSISTANT", "TOOL", "SYSTEM");

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeConversationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public KnowledgeConversationVO create(Long projectId, String title) {
        AuthUser user = requireUser();
        ensureProjectOwned(projectId, user.getUserId());
        String conversationId = "conv-" + UUID.randomUUID();
        jdbcTemplate.update(
            "insert into ai_conversation(conversation_id, user_id, project_id, title, status) " +
                "values(?, ?, ?, ?, 'ACTIVE')",
            conversationId,
            user.getUserId(),
            projectId,
            normalizeTitle(title)
        );
        return findOwnedActive(conversationId, user.getUserId());
    }

    public KnowledgeConversationVO createInitialForProject(Long projectId) {
        return create(projectId, "New conversation");
    }

    @Transactional
    public KnowledgeConversationVO ensureConversation(String conversationId, Long projectId, String title) {
        AuthUser user = requireUser();
        String normalizedId = trimToNull(conversationId);
        if (normalizedId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "conversationId is required");
        }
        ensureProjectOwned(projectId, user.getUserId());
        List<KnowledgeConversationVO> existing = jdbcTemplate.query(
            conversationSelect() + " where conversation_id = ?",
            this::mapConversation,
            normalizedId
        );
        if (!existing.isEmpty()) {
            KnowledgeConversationVO conversation = existing.get(0);
            if (!Objects.equals(conversation.getUserId(), user.getUserId())
                || !Objects.equals(conversation.getProjectId(), projectId)
                || "ARCHIVED".equals(conversation.getStatus())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
            }
            return conversation;
        }
        jdbcTemplate.update(
            "insert into ai_conversation(conversation_id, user_id, project_id, title, status) " +
                "values(?, ?, ?, ?, 'ACTIVE')",
            normalizedId,
            user.getUserId(),
            projectId,
            normalizeTitle(title)
        );
        return findOwnedActive(normalizedId, user.getUserId());
    }

    public List<KnowledgeConversationVO> listMine(Long projectId) {
        AuthUser user = requireUser();
        if (projectId == null) {
            return jdbcTemplate.query(
                conversationSelect() +
                    " where user_id = ? and status <> 'ARCHIVED' " +
                    "order by updated_at desc, conversation_id desc",
                this::mapConversation,
                user.getUserId()
            );
        }
        return jdbcTemplate.query(
            conversationSelect() +
                " where user_id = ? and project_id = ? and status <> 'ARCHIVED' " +
                "order by updated_at desc, conversation_id desc",
            this::mapConversation,
            user.getUserId(),
            projectId
        );
    }

    public KnowledgeConversationVO get(String conversationId) {
        AuthUser user = requireUser();
        KnowledgeConversationVO conversation = findOwnedActive(conversationId, user.getUserId());
        conversation.setMessages(listMessagesOwned(conversation.getConversationId(), user.getUserId()));
        return conversation;
    }

    public List<KnowledgeChatMessageVO> listMessages(String conversationId) {
        AuthUser user = requireUser();
        KnowledgeConversationVO conversation = findOwnedActive(conversationId, user.getUserId());
        return listMessagesOwned(conversation.getConversationId(), user.getUserId());
    }

    @Transactional
    public KnowledgeChatMessageVO appendMessage(String conversationId,
                                                 String runId,
                                                 String role,
                                                 String content,
                                                 String contentJson,
                                                 Integer tokenCount) {
        AuthUser user = requireUser();
        KnowledgeConversationVO conversation = findOwnedActive(conversationId, user.getUserId());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_chat_message(" +
                    "conversation_id, user_id, project_id, run_id, role, content, content_json, token_count" +
                    ") values(?, ?, ?, ?, ?, ?, ?, ?)",
                new String[]{"message_id"}
            );
            statement.setString(1, conversation.getConversationId());
            statement.setLong(2, conversation.getUserId());
            if (conversation.getProjectId() == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, conversation.getProjectId());
            }
            statement.setString(4, trimToNull(runId));
            statement.setString(5, normalizeRole(role));
            statement.setString(6, content);
            statement.setString(7, trimToNull(contentJson));
            if (tokenCount == null) {
                statement.setNull(8, Types.INTEGER);
            } else {
                statement.setInt(8, Math.max(tokenCount, 0));
            }
            return statement;
        }, keyHolder);
        Number messageId = keyHolder.getKey();
        if (messageId == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "message id missing");
        }
        jdbcTemplate.update(
            "update ai_conversation set last_message_id = ?, last_run_id = ?, updated_at = current_timestamp " +
                "where conversation_id = ? and user_id = ? and status <> 'ARCHIVED'",
            messageId.longValue(),
            trimToNull(runId),
            conversation.getConversationId(),
            user.getUserId()
        );
        return findMessage(messageId.longValue(), user.getUserId());
    }

    @Transactional
    public void archive(String conversationId) {
        AuthUser user = requireUser();
        KnowledgeConversationVO conversation = findOwnedActive(conversationId, user.getUserId());
        jdbcTemplate.update(
            "update ai_conversation set status = 'ARCHIVED', archived_at = current_timestamp, " +
                "updated_at = current_timestamp where conversation_id = ? and user_id = ?",
            conversation.getConversationId(),
            user.getUserId()
        );
    }

    private KnowledgeConversationVO findOwnedActive(String conversationId, Long userId) {
        String normalizedId = trimToNull(conversationId);
        if (normalizedId == null || userId == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
        }
        List<KnowledgeConversationVO> conversations = jdbcTemplate.query(
            conversationSelect() +
                " where conversation_id = ? and user_id = ? and status <> 'ARCHIVED'",
            this::mapConversation,
            normalizedId,
            userId
        );
        if (conversations.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "conversation not found");
        }
        return conversations.get(0);
    }

    private KnowledgeChatMessageVO findMessage(Long messageId, Long userId) {
        List<KnowledgeChatMessageVO> messages = jdbcTemplate.query(
            messageSelect() + " where message_id = ? and user_id = ? and deleted = 0",
            this::mapMessage,
            messageId,
            userId
        );
        if (messages.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat message not found");
        }
        return messages.get(0);
    }

    private List<KnowledgeChatMessageVO> listMessagesOwned(String conversationId, Long userId) {
        return jdbcTemplate.query(
            messageSelect() +
                " where conversation_id = ? and user_id = ? and deleted = 0 " +
                "order by created_at asc, message_id asc",
            this::mapMessage,
            conversationId,
            userId
        );
    }

    private void ensureProjectOwned(Long projectId, Long userId) {
        if (projectId == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from ai_project " +
                "where project_id = ? and user_id = ? and status <> 'ARCHIVED'",
            Integer.class,
            projectId,
            userId
        );
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project not found");
        }
    }

    private KnowledgeConversationVO mapConversation(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeConversationVO vo = new KnowledgeConversationVO();
        vo.setConversationId(rs.getString("conversation_id"));
        vo.setUserId(rs.getLong("user_id"));
        long projectId = rs.getLong("project_id");
        vo.setProjectId(rs.wasNull() ? null : projectId);
        vo.setTitle(rs.getString("title"));
        vo.setStatus(rs.getString("status"));
        long lastMessageId = rs.getLong("last_message_id");
        vo.setLastMessageId(rs.wasNull() ? null : lastMessageId);
        vo.setLastRunId(rs.getString("last_run_id"));
        vo.setLastRunStatus(rs.getString("last_run_status"));
        vo.setCreatedAt(localDateTime(rs.getTimestamp("created_at")));
        vo.setUpdatedAt(localDateTime(rs.getTimestamp("updated_at")));
        vo.setArchivedAt(localDateTime(rs.getTimestamp("archived_at")));
        return vo;
    }

    private KnowledgeChatMessageVO mapMessage(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeChatMessageVO vo = new KnowledgeChatMessageVO();
        vo.setMessageId(rs.getLong("message_id"));
        vo.setConversationId(rs.getString("conversation_id"));
        vo.setUserId(rs.getLong("user_id"));
        long projectId = rs.getLong("project_id");
        vo.setProjectId(rs.wasNull() ? null : projectId);
        vo.setRunId(rs.getString("run_id"));
        vo.setRole(rs.getString("role"));
        vo.setContent(rs.getString("content"));
        vo.setContentJson(rs.getString("content_json"));
        int tokenCount = rs.getInt("token_count");
        vo.setTokenCount(rs.wasNull() ? null : tokenCount);
        vo.setCreatedAt(localDateTime(rs.getTimestamp("created_at")));
        return vo;
    }

    private String conversationSelect() {
        return "select conversation_id, user_id, project_id, title, status, last_message_id, last_run_id, " +
            "(select r.status from ai_chat_run r where r.run_id = ai_conversation.last_run_id) " +
            "as last_run_status, created_at, updated_at, archived_at from ai_conversation";
    }

    private String messageSelect() {
        return "select message_id, conversation_id, user_id, project_id, run_id, role, content, content_json, " +
            "token_count, created_at from ai_chat_message";
    }

    private String normalizeTitle(String title) {
        String normalized = trimToNull(title);
        if (normalized == null) {
            return "New conversation";
        }
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private String normalizeRole(String role) {
        String normalized = trimToNull(role);
        normalized = normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
        if (!MESSAGE_ROLES.contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported chat message role");
        }
        return normalized;
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

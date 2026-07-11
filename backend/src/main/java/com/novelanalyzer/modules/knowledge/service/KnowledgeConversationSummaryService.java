package com.novelanalyzer.modules.knowledge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class KnowledgeConversationSummaryService {

    private static final int MAX_SUMMARY_CHARS = 12000;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeConversationSummaryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void updateSummary(Long userId,
                              Long projectId,
                              String conversationId,
                              String summary,
                              String sourceTraceId) {
        if (userId == null || isBlank(conversationId) || isBlank(summary)) {
            return;
        }
        String trimmedSummary = trim(summary);
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation_summary where conversation_id = ? and user_id = ?",
            Integer.class,
            conversationId,
            userId
        );
        if (count != null && count > 0) {
            jdbcTemplate.update(
                """
                    update ai_conversation_summary
                    set project_id = ?, summary = ?, source_trace_id = ?, updated_at = current_timestamp
                    where conversation_id = ? and user_id = ?
                    """,
                projectId,
                trimmedSummary,
                trimToNull(sourceTraceId),
                conversationId,
                userId
            );
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_conversation_summary(
                    conversation_id, user_id, project_id, summary, source_trace_id
                ) values (?, ?, ?, ?, ?)
                """,
            conversationId,
            userId,
            projectId,
            trimmedSummary,
            trimToNull(sourceTraceId)
        );
    }

    public Optional<ConversationSummary> readSummary(Long userId, String conversationId) {
        if (userId == null || isBlank(conversationId)) {
            return Optional.empty();
        }
        List<ConversationSummary> summaries = jdbcTemplate.query(
            """
                select conversation_id, user_id, project_id, summary, source_trace_id
                from ai_conversation_summary
                where conversation_id = ? and user_id = ?
                limit 1
                """,
            (rs, rowNum) -> new ConversationSummary(
                rs.getString("conversation_id"),
                rs.getLong("user_id"),
                (Long) rs.getObject("project_id"),
                rs.getString("summary"),
                rs.getString("source_trace_id")
            ),
            conversationId,
            userId
        );
        return summaries.stream().findFirst();
    }

    private String trim(String value) {
        String trimmed = value.trim();
        if (trimmed.length() <= MAX_SUMMARY_CHARS) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - MAX_SUMMARY_CHARS);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record ConversationSummary(
        String conversationId,
        Long userId,
        Long projectId,
        String summary,
        String sourceTraceId
    ) {
    }
}

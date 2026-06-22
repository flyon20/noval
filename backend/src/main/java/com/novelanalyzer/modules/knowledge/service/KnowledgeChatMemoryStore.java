package com.novelanalyzer.modules.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class KnowledgeChatMemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatMemoryStore.class);

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeChatMemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ChatMemory> find(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank() || userId == null) {
            return Optional.empty();
        }
        try {
            return jdbcTemplate.query(
                    """
                        SELECT conversation_id, user_id, summary
                        FROM knowledge_chat_memory
                        WHERE conversation_id = ? AND user_id = ? AND deleted = 0
                        LIMIT 1
                        """,
                    (rs, rowNum) -> new ChatMemory(
                        rs.getString("conversation_id"),
                        rs.getLong("user_id"),
                        rs.getString("summary")
                    ),
                    conversationId,
                    userId
                )
                .stream()
                .findFirst();
        } catch (DataAccessException ex) {
            LOGGER.debug("knowledge chat memory read skipped: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void save(String conversationId,
                     Long userId,
                     String summary,
                     String lastQuestion,
                     String lastAnswer,
                     String lastBookName,
                     String lastIntent) {
        if (conversationId == null || conversationId.isBlank() || userId == null || summary == null || summary.isBlank()) {
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM knowledge_chat_memory WHERE conversation_id = ?",
                Integer.class,
                conversationId
            );
            LocalDateTime now = LocalDateTime.now();
            if (count != null && count > 0) {
                jdbcTemplate.update(
                    """
                        UPDATE knowledge_chat_memory
                        SET user_id = ?, summary = ?, last_question = ?, last_answer = ?, last_book_name = ?,
                            last_intent = ?, update_time = ?, deleted = 0
                        WHERE conversation_id = ?
                        """,
                    userId,
                    summary,
                    lastQuestion,
                    lastAnswer,
                    lastBookName,
                    lastIntent,
                    Timestamp.valueOf(now),
                    conversationId
                );
                return;
            }
            jdbcTemplate.update(
                """
                    INSERT INTO knowledge_chat_memory(
                        conversation_id, user_id, summary, last_question, last_answer,
                        last_book_name, last_intent, create_time, update_time, deleted
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                conversationId,
                userId,
                summary,
                lastQuestion,
                lastAnswer,
                lastBookName,
                lastIntent,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                0
            );
        } catch (DataAccessException ex) {
            LOGGER.debug("knowledge chat memory write skipped: {}", ex.getMessage());
        }
    }

    public record ChatMemory(String conversationId, Long userId, String summary) {
    }
}

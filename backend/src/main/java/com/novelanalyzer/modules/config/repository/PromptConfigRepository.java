package com.novelanalyzer.modules.config.repository;

import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PromptConfigRepository {

    private static final RowMapper<PromptConfigEntity> ROW_MAPPER = (rs, rowNum) -> {
        PromptConfigEntity entity = new PromptConfigEntity();
        entity.setId(rs.getLong("id"));
        entity.setPromptType(rs.getString("prompt_type"));
        entity.setPromptName(rs.getString("prompt_name"));
        entity.setPromptContent(rs.getString("prompt_content"));
        entity.setModelName(rs.getString("model_name"));
        entity.setDifyWorkflowId(rs.getString("dify_workflow_id"));
        entity.setDifyApiKeyRef(rs.getString("dify_api_key_ref"));
        return entity;
    };

    private final JdbcTemplate jdbcTemplate;

    public PromptConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PromptConfigEntity> findDefaultByType(String promptType) {
        List<PromptConfigEntity> list = jdbcTemplate.query(
            """
            SELECT id, prompt_type, prompt_name, prompt_content, model_name, dify_workflow_id, dify_api_key_ref
            FROM prompt_config
            WHERE prompt_type = ? AND status = 1 AND deleted = 0
            ORDER BY is_default DESC, id ASC
            LIMIT 1
            """,
            ROW_MAPPER,
            promptType
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<PromptConfigEntity> findByTypeAndName(String promptType, String promptName) {
        List<PromptConfigEntity> list = jdbcTemplate.query(
            """
            SELECT id, prompt_type, prompt_name, prompt_content, model_name, dify_workflow_id, dify_api_key_ref
            FROM prompt_config
            WHERE prompt_type = ? AND prompt_name = ? AND deleted = 0
            LIMIT 1
            """,
            ROW_MAPPER,
            promptType,
            promptName
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Long saveOrUpdate(PromptConfigEntity entity) {
        Optional<PromptConfigEntity> existing = findByTypeAndName(entity.getPromptType(), entity.getPromptName());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                """
                UPDATE prompt_config
                SET prompt_content = ?, model_name = ?, update_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                entity.getPromptContent(),
                entity.getModelName(),
                id
            );
            return id;
        }
        jdbcTemplate.update(
            """
            INSERT INTO prompt_config
            (prompt_type, prompt_name, prompt_content, model_name, status, is_default, deleted, create_time, update_time)
            VALUES (?, ?, ?, ?, 1, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            entity.getPromptType(),
            entity.getPromptName(),
            entity.getPromptContent(),
            entity.getModelName()
        );
        List<Long> ids = jdbcTemplate.queryForList(
            "SELECT id FROM prompt_config WHERE prompt_type = ? AND prompt_name = ? ORDER BY id DESC LIMIT 1",
            Long.class,
            entity.getPromptType(),
            entity.getPromptName()
        );
        if (ids.isEmpty()) {
            throw new IllegalStateException("failed to save prompt_config");
        }
        return ids.get(0);
    }
}


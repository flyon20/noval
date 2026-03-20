package com.novelanalyzer.modules.analysis.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AnalysisRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long save(Long userId,
                     String platform,
                     Long bookId,
                     String analysisType,
                     Integer chapterCount,
                     Long promptConfigId,
                     String modelName,
                     String resultContent,
                     Integer tokenUsed,
                     Long costTime) {
        jdbcTemplate.update(
            """
            INSERT INTO analysis_result
            (user_id, platform, book_id, analysis_type, chapter_count, prompt_config_id, model_name, result_content, token_used, cost_time, create_time, update_time, deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """,
            userId,
            platform,
            bookId,
            analysisType,
            chapterCount,
            promptConfigId,
            modelName,
            resultContent,
            tokenUsed,
            costTime
        );
        List<Long> ids = jdbcTemplate.queryForList(
            """
            SELECT id FROM analysis_result
            WHERE book_id = ? AND analysis_type = ? AND deleted = 0
            ORDER BY id DESC LIMIT 1
            """,
            Long.class,
            bookId,
            analysisType
        );
        if (ids.isEmpty()) {
            throw new IllegalStateException("failed to persist analysis result");
        }
        return ids.get(0);
    }
}


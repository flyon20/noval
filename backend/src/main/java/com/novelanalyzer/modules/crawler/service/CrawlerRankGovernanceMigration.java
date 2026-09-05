package com.novelanalyzer.modules.crawler.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** Ensures the rank idempotency/fencing tables exist before the first refresh request. */
@Component
public class CrawlerRankGovernanceMigration {

    private final JdbcTemplate jdbcTemplate;

    public CrawlerRankGovernanceMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS crawler_rank_refresh_commit (
                idempotency_hash VARCHAR(64) PRIMARY KEY,
                request_fingerprint VARCHAR(64) NOT NULL,
                channel_code VARCHAR(50) NOT NULL,
                board_code VARCHAR(50) NOT NULL,
                snapshot_id BIGINT NOT NULL,
                snapshot_time TIMESTAMP NOT NULL,
                total_count INT NOT NULL DEFAULT 0,
                reused BOOLEAN NOT NULL DEFAULT FALSE,
                refresh_limited BOOLEAN NOT NULL DEFAULT FALSE,
                create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS crawler_rank_refresh_fence (
                rank_board_id BIGINT PRIMARY KEY,
                fencing_token BIGINT NOT NULL DEFAULT 0,
                update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }
}

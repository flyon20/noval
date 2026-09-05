package com.novelanalyzer.modules.crawler.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlerRankGovernanceMigrationTest {

    @Test
    void shouldCreateMissingGovernanceTablesAndRemainIdempotent() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:rank-governance-migration;MODE=MYSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE crawler_rank_refresh_commit (
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
        CrawlerRankGovernanceMigration migration = new CrawlerRankGovernanceMigration(jdbcTemplate);

        migration.ensureSchema();
        migration.ensureSchema();

        Integer tableCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_NAME IN ('CRAWLER_RANK_REFRESH_COMMIT', 'CRAWLER_RANK_REFRESH_FENCE')
            """, Integer.class);
        assertThat(tableCount).isEqualTo(2);
    }
}

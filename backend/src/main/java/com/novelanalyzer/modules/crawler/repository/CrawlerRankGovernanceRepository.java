package com.novelanalyzer.modules.crawler.repository;

import com.novelanalyzer.modules.crawler.model.RankRefreshCommitRecord;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlerRankGovernanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public CrawlerRankGovernanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RankRefreshCommitRecord> findCommitted(String idempotencyHash) {
        List<RankRefreshCommitRecord> rows = jdbcTemplate.query("""
                SELECT idempotency_hash, request_fingerprint, channel_code, board_code,
                       snapshot_id, snapshot_time, total_count, reused, refresh_limited
                FROM crawler_rank_refresh_commit
                WHERE idempotency_hash = ?
                """,
            (rs, rowNum) -> {
                RankRefreshResultVO result = new RankRefreshResultVO();
                result.setChannelCode(rs.getString("channel_code"));
                result.setBoardCode(rs.getString("board_code"));
                result.setSnapshotId(rs.getLong("snapshot_id"));
                Timestamp snapshotTime = rs.getTimestamp("snapshot_time");
                result.setSnapshotTime(snapshotTime == null ? null : snapshotTime.toLocalDateTime());
                result.setTotal(rs.getInt("total_count"));
                result.setReused(rs.getBoolean("reused"));
                result.setRefreshLimited(rs.getBoolean("refresh_limited"));
                result.setAnalysisTriggered(Boolean.FALSE);
                return new RankRefreshCommitRecord(
                    rs.getString("idempotency_hash"),
                    rs.getString("request_fingerprint"),
                    result
                );
            },
            idempotencyHash
        );
        return rows.stream().findFirst();
    }

    public boolean tryInsertCommitted(String idempotencyHash,
                                      String requestFingerprint,
                                      RankRefreshResultVO result) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO crawler_rank_refresh_commit(
                        idempotency_hash, request_fingerprint, channel_code, board_code,
                        snapshot_id, snapshot_time, total_count, reused, refresh_limited,
                        create_time, update_time
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                idempotencyHash,
                requestFingerprint,
                result.getChannelCode(),
                result.getBoardCode(),
                result.getSnapshotId(),
                result.getSnapshotTime(),
                result.getTotal(),
                Boolean.TRUE.equals(result.getReused()),
                Boolean.TRUE.equals(result.getRefreshLimited())
            ) == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public long nextFencingToken(Long rankBoardId) {
        jdbcTemplate.update("""
                INSERT INTO crawler_rank_refresh_fence(rank_board_id, fencing_token, update_time)
                VALUES (?, 0, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE rank_board_id = rank_board_id
                """,
            rankBoardId
        );
        jdbcTemplate.update("""
                UPDATE crawler_rank_refresh_fence
                SET fencing_token = fencing_token + 1,
                    update_time = CURRENT_TIMESTAMP
                WHERE rank_board_id = ?
                """,
            rankBoardId
        );
        Long token = jdbcTemplate.queryForObject(
            "SELECT fencing_token FROM crawler_rank_refresh_fence WHERE rank_board_id = ?",
            Long.class,
            rankBoardId
        );
        if (token == null) {
            throw new IllegalStateException("rank refresh fencing token was not created");
        }
        return token;
    }

    public boolean lockAndVerifyFencingToken(Long rankBoardId, long expectedToken) {
        Long current = jdbcTemplate.queryForObject(
            "SELECT fencing_token FROM crawler_rank_refresh_fence WHERE rank_board_id = ? FOR UPDATE",
            Long.class,
            rankBoardId
        );
        return current != null && current == expectedToken;
    }
}

package com.novelanalyzer.modules.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.novelanalyzer.modules.analysis.mapper.AnalysisResultMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.crawler.mapper.CrawlBookMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlRankMapper;
import com.novelanalyzer.modules.crawler.mapper.RankBoardMapper;
import com.novelanalyzer.modules.crawler.mapper.RankSnapshotMapper;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.data.vo.AnalysisHistorySearchMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class DataQueryRepository {

    private final AnalysisResultMapper analysisResultMapper;
    private final CrawlBookMapper crawlBookMapper;
    private final CrawlRankMapper crawlRankMapper;
    private final RankBoardMapper rankBoardMapper;
    private final RankSnapshotMapper rankSnapshotMapper;
    private final JdbcTemplate jdbcTemplate;
    private final boolean mysqlDatabase;

    public DataQueryRepository(AnalysisResultMapper analysisResultMapper,
                               CrawlBookMapper crawlBookMapper,
                               CrawlRankMapper crawlRankMapper,
                               RankBoardMapper rankBoardMapper,
                               RankSnapshotMapper rankSnapshotMapper,
                               JdbcTemplate jdbcTemplate) {
        this.analysisResultMapper = analysisResultMapper;
        this.crawlBookMapper = crawlBookMapper;
        this.crawlRankMapper = crawlRankMapper;
        this.rankBoardMapper = rankBoardMapper;
        this.rankSnapshotMapper = rankSnapshotMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mysqlDatabase = isMysql(jdbcTemplate.getDataSource());
    }

    public long countHistory(HistorySearchCriteria criteria) {
        if (hasKeyword(criteria)) {
            return countHistoryByKeyword(criteria);
        }
        Long count = analysisResultMapper.selectCount(buildHistoryWrapper(criteria));
        return count == null ? 0L : count;
    }

    public List<AnalysisResultEntity> findHistory(HistorySearchCriteria criteria, int page, int pageSize) {
        if (hasKeyword(criteria)) {
            return findHistoryByKeyword(criteria, page, pageSize);
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        long offset = (long) (safePage - 1) * safePageSize;
        return analysisResultMapper.selectList(
            buildHistoryQueryWrapper(criteria)
                .select(
                    "id",
                    "user_id",
                    "platform",
                    "book_id",
                    "channel_code",
                    "board_code",
                    "snapshot_id",
                    "analysis_type",
                    "chapter_count",
                    "prompt_config_id",
                    "model_name",
                    "SUBSTRING(result_content, 1, 512) AS result_content",
                    "token_used",
                    "cost_time",
                    "create_time",
                    "update_time",
                    "deleted"
                )
                .orderByDesc("create_time")
                .orderByDesc("id")
                .last("LIMIT " + safePageSize + " OFFSET " + offset)
        );
    }

    public Map<Long, AnalysisHistorySearchMeta> findHistorySearchMetaMap(String keyword, List<Long> resultIds) {
        String normalizedKeyword = normalizeKeyword(keyword);
        if (normalizedKeyword == null || resultIds == null || resultIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = resultIds.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(resultIds);
        Map<Long, AnalysisHistorySearchMeta> metaMap = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                SELECT analysis_result_id, search_text, structured_terms
                FROM analysis_result_search_doc
                WHERE deleted = 0 AND analysis_result_id IN (%s)
                """.formatted(placeholders),
            rs -> {
                Long resultId = rs.getLong("analysis_result_id");
                String searchText = rs.getString("search_text");
                String structuredTerms = rs.getString("structured_terms");
                List<String> fields = new ArrayList<>();
                if (containsIgnoreCase(searchText, normalizedKeyword)) {
                    fields.add("分析正文");
                }
                if (containsIgnoreCase(structuredTerms, normalizedKeyword)) {
                    fields.add("结构化标签");
                }
                List<String> snippets = new ArrayList<>();
                String snippet = buildSnippet(searchText, normalizedKeyword, 72);
                if (snippet != null) {
                    snippets.add(snippet);
                }
                if (snippets.isEmpty()) {
                    String termSnippet = buildSnippet(structuredTerms, normalizedKeyword, 72);
                    if (termSnippet != null) {
                        snippets.add(termSnippet);
                    }
                }
                metaMap.put(resultId, new AnalysisHistorySearchMeta(
                    fields.isEmpty() ? List.of("历史索引") : fields,
                    snippets,
                    null
                ));
            },
            args.toArray()
        );
        return metaMap;
    }

    private long countHistoryByKeyword(HistorySearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(1)
            FROM analysis_result ar
            JOIN analysis_result_search_doc sd ON sd.analysis_result_id = ar.id AND sd.deleted = 0
            WHERE ar.deleted = 0
            """);
        List<Object> args = new ArrayList<>();
        appendHistoryFilters(sql, args, criteria, "ar.");
        appendKeywordFilter(sql, args, criteria.keyword());
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    private List<AnalysisResultEntity> findHistoryByKeyword(HistorySearchCriteria criteria, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        long offset = (long) (safePage - 1) * safePageSize;
        StringBuilder sql = new StringBuilder("""
            SELECT ar.id, ar.user_id, ar.platform, ar.book_id, ar.channel_code, ar.board_code, ar.snapshot_id,
                   ar.analysis_type, ar.chapter_count, ar.prompt_config_id, ar.model_name,
                   SUBSTRING(ar.result_content, 1, 512) AS result_content,
                   ar.token_used, ar.cost_time, ar.create_time, ar.update_time, ar.deleted
            FROM analysis_result ar
            JOIN analysis_result_search_doc sd ON sd.analysis_result_id = ar.id AND sd.deleted = 0
            WHERE ar.deleted = 0
            """);
        List<Object> args = new ArrayList<>();
        appendHistoryFilters(sql, args, criteria, "ar.");
        appendKeywordFilter(sql, args, criteria.keyword());
        sql.append(" ORDER BY ar.create_time DESC, ar.id DESC LIMIT ? OFFSET ?");
        args.add(safePageSize);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            AnalysisResultEntity entity = new AnalysisResultEntity();
            entity.setId(rs.getLong("id"));
            entity.setUserId(rs.getLong("user_id"));
            entity.setPlatform(rs.getString("platform"));
            long bookId = rs.getLong("book_id");
            entity.setBookId(rs.wasNull() ? null : bookId);
            entity.setChannelCode(rs.getString("channel_code"));
            entity.setBoardCode(rs.getString("board_code"));
            long snapshotId = rs.getLong("snapshot_id");
            entity.setSnapshotId(rs.wasNull() ? null : snapshotId);
            entity.setAnalysisType(rs.getString("analysis_type"));
            int chapterCount = rs.getInt("chapter_count");
            entity.setChapterCount(rs.wasNull() ? null : chapterCount);
            long promptConfigId = rs.getLong("prompt_config_id");
            entity.setPromptConfigId(rs.wasNull() ? null : promptConfigId);
            entity.setModelName(rs.getString("model_name"));
            entity.setResultContent(rs.getString("result_content"));
            int tokenUsed = rs.getInt("token_used");
            entity.setTokenUsed(rs.wasNull() ? null : tokenUsed);
            long costTime = rs.getLong("cost_time");
            entity.setCostTime(rs.wasNull() ? null : costTime);
            entity.setCreateTime(rs.getTimestamp("create_time") == null ? null : rs.getTimestamp("create_time").toLocalDateTime());
            entity.setUpdateTime(rs.getTimestamp("update_time") == null ? null : rs.getTimestamp("update_time").toLocalDateTime());
            int deleted = rs.getInt("deleted");
            entity.setDeleted(rs.wasNull() ? null : deleted);
            return entity;
        }, args.toArray());
    }

    private void appendHistoryFilters(StringBuilder sql, List<Object> args, HistorySearchCriteria criteria, String prefix) {
        HistorySearchCriteria safeCriteria = criteria == null ? new HistorySearchCriteria() : criteria;
        appendStringFilter(sql, args, prefix + "platform", safeCriteria.platform());
        if (safeCriteria.bookId() != null) {
            sql.append(" AND ").append(prefix).append("book_id = ?");
            args.add(safeCriteria.bookId());
        }
        appendStringFilter(sql, args, prefix + "analysis_type", safeCriteria.analysisType());
        appendStringFilter(sql, args, prefix + "channel_code", safeCriteria.channelCode());
        appendStringFilter(sql, args, prefix + "board_code", safeCriteria.boardCode());
        if (safeCriteria.chapterCount() != null) {
            sql.append(" AND ").append(prefix).append("chapter_count = ?");
            args.add(safeCriteria.chapterCount());
        }
        appendStringFilter(sql, args, prefix + "model_name", safeCriteria.modelName());
        if (safeCriteria.userId() != null) {
            sql.append(" AND ").append(prefix).append("user_id = ?");
            args.add(safeCriteria.userId());
        }
        if (safeCriteria.startTime() != null) {
            sql.append(" AND ").append(prefix).append("create_time >= ?");
            args.add(safeCriteria.startTime());
        }
        if (safeCriteria.endTime() != null) {
            sql.append(" AND ").append(prefix).append("create_time <= ?");
            args.add(safeCriteria.endTime());
        }
    }

    private void appendStringFilter(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private void appendKeywordFilter(StringBuilder sql, List<Object> args, String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        if (mysqlDatabase) {
            sql.append(" AND MATCH(sd.search_text, sd.structured_terms) AGAINST (? IN NATURAL LANGUAGE MODE)");
            args.add(normalizedKeyword);
            return;
        }
        sql.append(" AND (LOWER(sd.search_text) LIKE ? OR LOWER(sd.structured_terms) LIKE ?)");
        String pattern = "%" + normalizedKeyword.toLowerCase(Locale.ROOT) + "%";
        args.add(pattern);
        args.add(pattern);
    }

    private boolean isMysql(DataSource dataSource) {
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
        } catch (SQLException ex) {
            return false;
        }
    }

    private boolean hasKeyword(HistorySearchCriteria criteria) {
        return criteria != null && normalizeKeyword(criteria.keyword()) != null;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.trim().replaceAll("\\s+", " ");
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return text != null
            && keyword != null
            && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String buildSnippet(String text, String keyword, int radius) {
        if (text == null || keyword == null) {
            return null;
        }
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        String lowerText = normalizedText.toLowerCase(Locale.ROOT);
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        int index = lowerText.indexOf(lowerKeyword);
        if (index < 0) {
            return null;
        }
        int start = Math.max(0, index - radius);
        int end = Math.min(normalizedText.length(), index + keyword.length() + radius);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < normalizedText.length() ? "..." : "";
        return prefix + normalizedText.substring(start, end).trim() + suffix;
    }

    public Optional<AnalysisResultEntity> findHistoryDetail(Long id, Long userId) {
        if (id == null) {
            return Optional.empty();
        }
        LambdaQueryWrapper<AnalysisResultEntity> wrapper = new LambdaQueryWrapper<AnalysisResultEntity>()
            .eq(AnalysisResultEntity::getDeleted, 0)
            .eq(AnalysisResultEntity::getId, id)
            .eq(userId != null, AnalysisResultEntity::getUserId, userId)
            .last("LIMIT 1");
        return Optional.ofNullable(analysisResultMapper.selectOne(wrapper));
    }

    private LambdaQueryWrapper<AnalysisResultEntity> buildHistoryWrapper(HistorySearchCriteria criteria) {
        HistorySearchCriteria safeCriteria = criteria == null ? new HistorySearchCriteria() : criteria;
        LambdaQueryWrapper<AnalysisResultEntity> wrapper = new LambdaQueryWrapper<AnalysisResultEntity>()
            .eq(AnalysisResultEntity::getDeleted, 0);
        String platform = safeCriteria.platform();
        if (platform != null && !platform.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getPlatform, platform.trim());
        }
        Long bookId = safeCriteria.bookId();
        if (bookId != null) {
            wrapper.eq(AnalysisResultEntity::getBookId, bookId);
        }
        String analysisType = safeCriteria.analysisType();
        if (analysisType != null && !analysisType.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getAnalysisType, analysisType.trim());
        }
        String channelCode = safeCriteria.channelCode();
        if (channelCode != null && !channelCode.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getChannelCode, channelCode.trim());
        }
        String boardCode = safeCriteria.boardCode();
        if (boardCode != null && !boardCode.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getBoardCode, boardCode.trim());
        }
        Integer chapterCount = safeCriteria.chapterCount();
        if (chapterCount != null) {
            wrapper.eq(AnalysisResultEntity::getChapterCount, chapterCount);
        }
        String modelName = safeCriteria.modelName();
        if (modelName != null && !modelName.isBlank()) {
            wrapper.eq(AnalysisResultEntity::getModelName, modelName.trim());
        }
        Long userId = safeCriteria.userId();
        if (userId != null) {
            wrapper.eq(AnalysisResultEntity::getUserId, userId);
        }
        LocalDateTime startTime = safeCriteria.startTime();
        if (startTime != null) {
            wrapper.ge(AnalysisResultEntity::getCreateTime, startTime);
        }
        LocalDateTime endTime = safeCriteria.endTime();
        if (endTime != null) {
            wrapper.le(AnalysisResultEntity::getCreateTime, endTime);
        }
        return wrapper;
    }

    private QueryWrapper<AnalysisResultEntity> buildHistoryQueryWrapper(HistorySearchCriteria criteria) {
        HistorySearchCriteria safeCriteria = criteria == null ? new HistorySearchCriteria() : criteria;
        QueryWrapper<AnalysisResultEntity> wrapper = new QueryWrapper<AnalysisResultEntity>()
            .eq("deleted", 0);
        String platform = safeCriteria.platform();
        if (platform != null && !platform.isBlank()) {
            wrapper.eq("platform", platform.trim());
        }
        Long bookId = safeCriteria.bookId();
        if (bookId != null) {
            wrapper.eq("book_id", bookId);
        }
        String analysisType = safeCriteria.analysisType();
        if (analysisType != null && !analysisType.isBlank()) {
            wrapper.eq("analysis_type", analysisType.trim());
        }
        String channelCode = safeCriteria.channelCode();
        if (channelCode != null && !channelCode.isBlank()) {
            wrapper.eq("channel_code", channelCode.trim());
        }
        String boardCode = safeCriteria.boardCode();
        if (boardCode != null && !boardCode.isBlank()) {
            wrapper.eq("board_code", boardCode.trim());
        }
        Integer chapterCount = safeCriteria.chapterCount();
        if (chapterCount != null) {
            wrapper.eq("chapter_count", chapterCount);
        }
        String modelName = safeCriteria.modelName();
        if (modelName != null && !modelName.isBlank()) {
            wrapper.eq("model_name", modelName.trim());
        }
        Long userId = safeCriteria.userId();
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        LocalDateTime startTime = safeCriteria.startTime();
        if (startTime != null) {
            wrapper.ge("create_time", startTime);
        }
        LocalDateTime endTime = safeCriteria.endTime();
        if (endTime != null) {
            wrapper.le("create_time", endTime);
        }
        return wrapper;
    }

    public Map<Long, CrawlBookEntity> findBookMap(List<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return Map.of();
        }
        return crawlBookMapper.selectBatchIds(bookIds).stream()
            .filter(item -> item.getDeleted() == null || item.getDeleted() == 0)
            .collect(Collectors.toMap(CrawlBookEntity::getId, Function.identity(), (left, right) -> left));
    }

    public record HistorySearchCriteria(
        String platform,
        Long bookId,
        String analysisType,
        String channelCode,
        String boardCode,
        Integer chapterCount,
        String modelName,
        String keyword,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long userId
    ) {
        public HistorySearchCriteria() {
            this(null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public Optional<RankBoardEntity> findBoard(String platform, String channelCode, String boardCode) {
        RankBoardEntity entity = rankBoardMapper.selectOne(
            new LambdaQueryWrapper<RankBoardEntity>()
                .eq(RankBoardEntity::getDeleted, 0)
                .eq(RankBoardEntity::getPlatform, platform)
                .eq(RankBoardEntity::getChannelCode, channelCode)
                .eq(RankBoardEntity::getBoardCode, boardCode)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<RankSnapshotEntity> findRecentSnapshots(Long boardId, int limit) {
        return rankSnapshotMapper.selectList(
            new LambdaQueryWrapper<RankSnapshotEntity>()
                .eq(RankSnapshotEntity::getDeleted, 0)
                .eq(RankSnapshotEntity::getRankBoardId, boardId)
                .orderByDesc(RankSnapshotEntity::getSnapshotTime)
                .last("LIMIT " + Math.max(limit, 1))
        );
    }

    public List<CrawlRankEntity> findRanksBySnapshotIds(List<Long> snapshotIds) {
        if (snapshotIds == null || snapshotIds.isEmpty()) {
            return List.of();
        }
        return crawlRankMapper.selectList(
            new LambdaQueryWrapper<CrawlRankEntity>()
                .eq(CrawlRankEntity::getDeleted, 0)
                .in(CrawlRankEntity::getSnapshotId, snapshotIds)
                .orderByDesc(CrawlRankEntity::getCrawlTime)
                .orderByAsc(CrawlRankEntity::getRankNo)
        );
    }

    public Optional<AnalysisResultEntity> findLatestBoardThemeResult(String platform,
                                                                     String channelCode,
                                                                     String boardCode) {
        AnalysisResultEntity entity = analysisResultMapper.selectOne(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getDeleted, 0)
                .eq(AnalysisResultEntity::getPlatform, platform)
                .eq(AnalysisResultEntity::getChannelCode, channelCode)
                .eq(AnalysisResultEntity::getBoardCode, boardCode)
                .eq(AnalysisResultEntity::getAnalysisType, "theme")
                .orderByDesc(AnalysisResultEntity::getCreateTime)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<AnalysisResultEntity> findRecentBoardThemeResults(String platform,
                                                                  String channelCode,
                                                                  String boardCode,
                                                                  int limit) {
        return analysisResultMapper.selectList(
            new LambdaQueryWrapper<AnalysisResultEntity>()
                .eq(AnalysisResultEntity::getDeleted, 0)
                .eq(AnalysisResultEntity::getPlatform, platform)
                .eq(AnalysisResultEntity::getChannelCode, channelCode)
                .eq(AnalysisResultEntity::getBoardCode, boardCode)
                .eq(AnalysisResultEntity::getAnalysisType, "theme")
                .orderByDesc(AnalysisResultEntity::getCreateTime)
                .last("LIMIT " + Math.max(1, limit))
        );
    }
}

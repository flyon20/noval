package com.novelanalyzer.modules.crawler.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlBookMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlChapterMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlRankMapper;
import com.novelanalyzer.modules.crawler.mapper.RankBoardMapper;
import com.novelanalyzer.modules.crawler.mapper.RankSnapshotMapper;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlChapterEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class CrawlerRepository {

    private final CrawlBookMapper crawlBookMapper;
    private final CrawlRankMapper crawlRankMapper;
    private final CrawlChapterMapper crawlChapterMapper;
    private final RankBoardMapper rankBoardMapper;
    private final RankSnapshotMapper rankSnapshotMapper;
    private final JdbcTemplate jdbcTemplate;

    public CrawlerRepository(CrawlBookMapper crawlBookMapper,
                             CrawlRankMapper crawlRankMapper,
                             CrawlChapterMapper crawlChapterMapper,
                             RankBoardMapper rankBoardMapper,
                             RankSnapshotMapper rankSnapshotMapper,
                             JdbcTemplate jdbcTemplate) {
        this.crawlBookMapper = crawlBookMapper;
        this.crawlRankMapper = crawlRankMapper;
        this.crawlChapterMapper = crawlChapterMapper;
        this.rankBoardMapper = rankBoardMapper;
        this.rankSnapshotMapper = rankSnapshotMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long saveOrUpdateBook(String platform,
                                 String platformBookId,
                                 String bookName,
                                 String author,
                                 String intro,
                                 String bookUrl) {
        CrawlBookEntity existing = findBookByPlatformAndPlatformBookId(platform, platformBookId).orElseGet(() ->
            findBookByPlatformAndBookUrl(platform, bookUrl).orElse(null)
        );
        if (existing != null) {
            mergeBook(existing, platformBookId, bookName, author, intro, bookUrl);
            crawlBookMapper.updateById(existing);
            return existing.getId();
        }

        CrawlBookEntity entity = new CrawlBookEntity();
        entity.setPlatform(platform);
        mergeBook(entity, platformBookId, bookName, author, intro, bookUrl);
        entity.setDeleted(0);
        crawlBookMapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("failed to generate crawl_book id");
        }
        return entity.getId();
    }

    public void saveRankItem(String platform,
                             String category,
                             Integer rankNo,
                             Long bookId,
                             String bookName,
                             String bookUrl,
                             String author,
                             String intro,
                             LocalDateTime snapshotTime) {
        saveRankItem(platform, category, null, null, null, rankNo, bookId, bookName, bookUrl, author, intro, snapshotTime);
    }

    public void saveRankItem(String platform,
                             String category,
                             String channelCode,
                             String boardCode,
                             Long snapshotId,
                             Integer rankNo,
                             Long bookId,
                             String bookName,
                             String bookUrl,
                             String author,
                             String intro,
                             LocalDateTime snapshotTime) {
        CrawlRankEntity entity = new CrawlRankEntity();
        entity.setSnapshotId(snapshotId);
        entity.setPlatform(platform);
        entity.setCategory(category);
        entity.setChannelCode(channelCode);
        entity.setBoardCode(boardCode);
        entity.setRankNo(rankNo);
        entity.setBookId(bookId);
        entity.setBookName(bookName);
        entity.setBookUrl(bookUrl);
        entity.setAuthor(author);
        entity.setIntro(intro);
        entity.setCrawlTime(snapshotTime);
        entity.setDeleted(0);
        crawlRankMapper.insert(entity);
    }

    public Optional<CrawlBookEntity> findBookById(Long id) {
        CrawlBookEntity book = crawlBookMapper.selectOne(
            new LambdaQueryWrapper<CrawlBookEntity>()
                .eq(CrawlBookEntity::getId, id)
                .eq(CrawlBookEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(book);
    }

    public void saveOrUpdateChapter(String platform,
                                    Long bookId,
                                    Integer chapterNo,
                                    String chapterTitle,
                                    String content) {
        CrawlChapterEntity existing = crawlChapterMapper.selectOne(
            new LambdaQueryWrapper<CrawlChapterEntity>()
                .eq(CrawlChapterEntity::getBookId, bookId)
                .eq(CrawlChapterEntity::getChapterNo, chapterNo)
                .eq(CrawlChapterEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        int wordCount = content == null ? 0 : content.length();
        if (existing != null) {
            existing.setChapterTitle(chapterTitle);
            existing.setContent(content);
            existing.setWordCount(wordCount);
            existing.setCrawlTime(LocalDateTime.now());
            crawlChapterMapper.updateById(existing);
            return;
        }

        CrawlChapterEntity entity = new CrawlChapterEntity();
        entity.setPlatform(platform);
        entity.setBookId(bookId);
        entity.setChapterNo(chapterNo);
        entity.setChapterTitle(chapterTitle);
        entity.setContent(content);
        entity.setWordCount(wordCount);
        entity.setCrawlTime(LocalDateTime.now());
        entity.setDeleted(0);
        crawlChapterMapper.insert(entity);
    }

    public List<ChapterVO> findChapters(Long bookId, int limit) {
        return crawlChapterMapper.selectList(
                new LambdaQueryWrapper<CrawlChapterEntity>()
                    .eq(CrawlChapterEntity::getBookId, bookId)
                    .eq(CrawlChapterEntity::getDeleted, 0)
                    .orderByAsc(CrawlChapterEntity::getChapterNo)
                    .last("LIMIT " + limit)
            ).stream()
            .sorted(Comparator.comparing(CrawlChapterEntity::getChapterNo))
            .map(this::toChapterVO)
            .collect(Collectors.toList());
    }

    public List<CrawlRankEntity> findRanks(String platform, String category) {
        LambdaQueryWrapper<CrawlRankEntity> wrapper = new LambdaQueryWrapper<CrawlRankEntity>()
            .eq(CrawlRankEntity::getPlatform, platform)
            .eq(CrawlRankEntity::getDeleted, 0)
            .orderByDesc(CrawlRankEntity::getCrawlTime)
            .orderByAsc(CrawlRankEntity::getRankNo);
        if (category != null && !category.isBlank()) {
            wrapper.eq(CrawlRankEntity::getCategory, category);
        }
        return crawlRankMapper.selectList(wrapper);
    }

    public List<CrawlRankEntity> findLatestRankSnapshot(String platform, String category) {
        List<CrawlRankEntity> ranks = findRanks(platform, category);
        if (ranks.isEmpty()) {
            return List.of();
        }
        LocalDateTime latestSnapshotTime = ranks.get(0).getCrawlTime();
        return ranks.stream()
            .filter(item -> Objects.equals(item.getCrawlTime(), latestSnapshotTime))
            .sorted(Comparator.comparing(CrawlRankEntity::getRankNo))
            .toList();
    }

    public LocalDateTime findLatestRankSnapshotTime(String platform, String category) {
        return findLatestRankSnapshot(platform, category).stream()
            .map(CrawlRankEntity::getCrawlTime)
            .findFirst()
            .orElse(null);
    }

    public RankBoardEntity saveOrUpdateRankBoard(String platform,
                                                 String channelCode,
                                                 String channelName,
                                                 String boardCode,
                                                 String boardName) {
        RankBoardEntity existing = findRankBoard(platform, channelCode, boardCode).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            jdbcTemplate.update(
                """
                    INSERT INTO rank_board(platform, channel_code, board_code, board_name, description, create_time, update_time, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                platform,
                channelCode,
                boardCode,
                boardName,
                channelName,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)
            );
            return findRankBoard(platform, channelCode, boardCode)
                .orElseThrow(() -> new IllegalStateException("failed to create rank_board"));
        }
        existing.setBoardName(boardName);
        existing.setDescription(channelName);
        existing.setUpdateTime(now);
        rankBoardMapper.updateById(existing);
        return existing;
    }

    public Optional<RankBoardEntity> findRankBoard(String platform, String channelCode, String boardCode) {
        RankBoardEntity entity = rankBoardMapper.selectOne(
            new LambdaQueryWrapper<RankBoardEntity>()
                .eq(RankBoardEntity::getPlatform, platform)
                .eq(RankBoardEntity::getChannelCode, channelCode)
                .eq(RankBoardEntity::getBoardCode, boardCode)
                .eq(RankBoardEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public List<RankBoardEntity> findRankBoards(String platform) {
        return rankBoardMapper.selectList(
            new LambdaQueryWrapper<RankBoardEntity>()
                .eq(RankBoardEntity::getPlatform, platform)
                .eq(RankBoardEntity::getDeleted, 0)
                .orderByAsc(RankBoardEntity::getChannelCode)
                .orderByAsc(RankBoardEntity::getBoardCode)
        );
    }

    public RankSnapshotEntity saveRankSnapshot(Long rankBoardId, LocalDateTime snapshotTime, int recordCount) {
        RankSnapshotEntity entity = new RankSnapshotEntity();
        entity.setRankBoardId(rankBoardId);
        entity.setSnapshotTime(snapshotTime);
        entity.setRecordCount(recordCount);
        entity.setCreateTime(snapshotTime);
        entity.setUpdateTime(snapshotTime);
        entity.setDeleted(0);
        rankSnapshotMapper.insert(entity);
        return entity;
    }

    public Optional<RankSnapshotEntity> findLatestBoardSnapshot(Long rankBoardId) {
        RankSnapshotEntity entity = rankSnapshotMapper.selectOne(
            new LambdaQueryWrapper<RankSnapshotEntity>()
                .eq(RankSnapshotEntity::getRankBoardId, rankBoardId)
                .eq(RankSnapshotEntity::getDeleted, 0)
                .orderByDesc(RankSnapshotEntity::getSnapshotTime)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(entity);
    }

    public Optional<RankSnapshotEntity> findLatestBoardSnapshot(String platform, String channelCode, String boardCode) {
        return findRankBoard(platform, channelCode, boardCode)
            .flatMap(board -> findLatestBoardSnapshot(board.getId()));
    }

    public List<CrawlRankEntity> findRankPageBySnapshot(Long snapshotId, int offset, int limit) {
        return crawlRankMapper.selectList(
            new LambdaQueryWrapper<CrawlRankEntity>()
                .eq(CrawlRankEntity::getSnapshotId, snapshotId)
                .eq(CrawlRankEntity::getDeleted, 0)
                .orderByAsc(CrawlRankEntity::getRankNo)
                .last("LIMIT " + limit + " OFFSET " + offset)
        );
    }

    public int countRanksBySnapshot(Long snapshotId) {
        Long count = crawlRankMapper.selectCount(
            new LambdaQueryWrapper<CrawlRankEntity>()
                .eq(CrawlRankEntity::getSnapshotId, snapshotId)
                .eq(CrawlRankEntity::getDeleted, 0)
        );
        return count == null ? 0 : count.intValue();
    }

    public int countRecentSuccessfulForceRefreshes(String platform, String category, LocalDateTime windowStart) {
        Integer count = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(1)
                FROM crawler_task
                WHERE task_type = ?
                  AND platform = ?
                  AND status = 2
                  AND create_time >= ?
                  AND request_json LIKE ?
                  AND request_json LIKE ?
                """,
            Integer.class,
            "rank_refresh",
            platform,
            Timestamp.valueOf(windowStart),
            "%\"refreshMode\":\"FORCE\"%",
            "%\"category\":\"" + category + "\"%"
        );
        return count == null ? 0 : count;
    }

    public int countRecentSuccessfulForceRefreshes(String platform,
                                                   String channelCode,
                                                   String boardCode,
                                                   LocalDateTime windowStart) {
        Integer count = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(1)
                FROM crawler_task
                WHERE task_type = ?
                  AND platform = ?
                  AND status = 2
                  AND create_time >= ?
                  AND request_json LIKE ?
                  AND request_json LIKE ?
                  AND request_json LIKE ?
                """,
            Integer.class,
            "rank_refresh",
            platform,
            Timestamp.valueOf(windowStart),
            "%\"refreshMode\":\"FORCE\"%",
            "%\"channelCode\":\"" + channelCode + "\"%",
            "%\"boardCode\":\"" + boardCode + "\"%"
        );
        return count == null ? 0 : count;
    }

    public void saveRankRefreshTask(String platform,
                                    String category,
                                    String refreshMode,
                                    String forceReason,
                                    int status,
                                    String errorMessage,
                                    LocalDateTime startTime,
                                    LocalDateTime endTime) {
        String requestJson = "{\"platform\":\"" + platform
            + "\",\"category\":\"" + category
            + "\",\"refreshMode\":\"" + refreshMode
            + "\",\"forceReason\":\"" + (forceReason == null ? "" : forceReason.replace("\"", "'"))
            + "\"}";
        jdbcTemplate.update(
            """
                INSERT INTO crawler_task(task_type, platform, request_json, status, error_message, start_time, end_time, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "rank_refresh",
            platform,
            requestJson,
            status,
            errorMessage,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime)
        );
    }

    public void saveRankRefreshTask(String platform,
                                    String channelCode,
                                    String boardCode,
                                    String refreshMode,
                                    String forceReason,
                                    int status,
                                    String errorMessage,
                                    LocalDateTime startTime,
                                    LocalDateTime endTime) {
        String requestJson = "{\"platform\":\"" + platform
            + "\",\"channelCode\":\"" + channelCode
            + "\",\"boardCode\":\"" + boardCode
            + "\",\"refreshMode\":\"" + refreshMode
            + "\",\"forceReason\":\"" + (forceReason == null ? "" : forceReason.replace("\"", "'"))
            + "\"}";
        jdbcTemplate.update(
            """
                INSERT INTO crawler_task(task_type, platform, request_json, status, error_message, start_time, end_time, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "rank_refresh",
            platform,
            requestJson,
            status,
            errorMessage,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime)
        );
    }

    public List<CrawlBookEntity> findBooksByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return crawlBookMapper.selectBatchIds(ids).stream()
            .filter(item -> item.getDeleted() == null || item.getDeleted() == 0)
            .collect(Collectors.toList());
    }

    private ChapterVO toChapterVO(CrawlChapterEntity entity) {
        ChapterVO vo = new ChapterVO();
        vo.setBookId(entity.getBookId());
        vo.setChapterNo(entity.getChapterNo());
        vo.setChapterTitle(entity.getChapterTitle());
        vo.setContent(entity.getContent());
        vo.setWordCount(entity.getWordCount());
        return vo;
    }

    private Optional<CrawlBookEntity> findBookByPlatformAndPlatformBookId(String platform, String platformBookId) {
        if (platformBookId == null || platformBookId.isBlank()) {
            return Optional.empty();
        }
        CrawlBookEntity book = crawlBookMapper.selectOne(
            new LambdaQueryWrapper<CrawlBookEntity>()
                .eq(CrawlBookEntity::getPlatform, platform)
                .eq(CrawlBookEntity::getPlatformBookId, platformBookId)
                .eq(CrawlBookEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(book);
    }

    private Optional<CrawlBookEntity> findBookByPlatformAndBookUrl(String platform, String bookUrl) {
        if (bookUrl == null || bookUrl.isBlank()) {
            return Optional.empty();
        }
        CrawlBookEntity book = crawlBookMapper.selectOne(
            new LambdaQueryWrapper<CrawlBookEntity>()
                .eq(CrawlBookEntity::getPlatform, platform)
                .eq(CrawlBookEntity::getBookUrl, bookUrl)
                .eq(CrawlBookEntity::getDeleted, 0)
                .last("LIMIT 1")
        );
        return Optional.ofNullable(book);
    }

    private void mergeBook(CrawlBookEntity entity,
                           String platformBookId,
                           String bookName,
                           String author,
                           String intro,
                           String bookUrl) {
        if (platformBookId != null && !platformBookId.isBlank()) {
            entity.setPlatformBookId(platformBookId);
        }
        if (bookName != null && !bookName.isBlank()) {
            entity.setBookName(bookName);
        }
        if (author != null && !author.isBlank()) {
            entity.setAuthor(author);
        }
        if (intro != null && !intro.isBlank()) {
            entity.setIntro(intro);
        }
        if (bookUrl != null && !bookUrl.isBlank()) {
            entity.setBookUrl(bookUrl);
        }
        entity.setLastCrawlTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
    }
}

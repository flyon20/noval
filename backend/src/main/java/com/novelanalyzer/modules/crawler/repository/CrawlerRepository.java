package com.novelanalyzer.modules.crawler.repository;

import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CrawlerRepository {

    private static final RowMapper<CrawlBookEntity> BOOK_ROW_MAPPER = (rs, rowNum) -> {
        CrawlBookEntity entity = new CrawlBookEntity();
        entity.setId(rs.getLong("id"));
        entity.setPlatform(rs.getString("platform"));
        entity.setPlatformBookId(rs.getString("platform_book_id"));
        entity.setBookName(rs.getString("book_name"));
        entity.setAuthor(rs.getString("author"));
        entity.setIntro(rs.getString("intro"));
        entity.setBookUrl(rs.getString("book_url"));
        return entity;
    };

    private static final RowMapper<ChapterVO> CHAPTER_ROW_MAPPER = (rs, rowNum) -> {
        ChapterVO vo = new ChapterVO();
        vo.setBookId(rs.getLong("book_id"));
        vo.setChapterNo(rs.getInt("chapter_no"));
        vo.setChapterTitle(rs.getString("chapter_title"));
        vo.setContent(rs.getString("content"));
        vo.setWordCount(rs.getInt("word_count"));
        return vo;
    };

    private final JdbcTemplate jdbcTemplate;

    public CrawlerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long saveOrUpdateBook(String platform,
                                 String platformBookId,
                                 String bookName,
                                 String author,
                                 String intro,
                                 String bookUrl) {
        List<Long> exists = jdbcTemplate.queryForList(
            "SELECT id FROM crawl_book WHERE platform = ? AND book_url = ? AND deleted = 0 LIMIT 1",
            Long.class,
            platform,
            bookUrl
        );
        if (!exists.isEmpty()) {
            Long bookId = exists.get(0);
            jdbcTemplate.update(
                """
                UPDATE crawl_book
                SET platform_book_id = ?, book_name = ?, author = ?, intro = ?, last_crawl_time = ?, update_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                platformBookId,
                bookName,
                author,
                intro,
                Timestamp.valueOf(LocalDateTime.now()),
                bookId
            );
            return bookId;
        }
        jdbcTemplate.update(
            """
            INSERT INTO crawl_book
            (platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """,
            platform,
            platformBookId,
            bookName,
            author,
            intro,
            bookUrl,
            Timestamp.valueOf(LocalDateTime.now())
        );
        List<Long> inserted = jdbcTemplate.queryForList(
            "SELECT id FROM crawl_book WHERE platform = ? AND book_url = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
            Long.class,
            platform,
            bookUrl
        );
        if (inserted.isEmpty()) {
            throw new IllegalStateException("failed to generate crawl_book id");
        }
        return inserted.get(0);
    }

    public void saveRankItem(String platform,
                             String category,
                             Integer rankNo,
                             Long bookId,
                             String bookName,
                             String bookUrl,
                             String author,
                             String intro) {
        jdbcTemplate.update(
            """
            INSERT INTO crawl_rank
            (platform, category, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """,
            platform,
            category,
            rankNo,
            bookId,
            bookName,
            bookUrl,
            author,
            intro
        );
    }

    public Optional<CrawlBookEntity> findBookById(Long id) {
        List<CrawlBookEntity> books = jdbcTemplate.query(
            "SELECT * FROM crawl_book WHERE id = ? AND deleted = 0 LIMIT 1",
            BOOK_ROW_MAPPER,
            id
        );
        if (books.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(books.get(0));
    }

    public void saveOrUpdateChapter(String platform,
                                    Long bookId,
                                    Integer chapterNo,
                                    String chapterTitle,
                                    String content) {
        List<Long> exists = jdbcTemplate.queryForList(
            "SELECT id FROM crawl_chapter WHERE book_id = ? AND chapter_no = ? AND deleted = 0 LIMIT 1",
            Long.class,
            bookId,
            chapterNo
        );
        int wordCount = content == null ? 0 : content.length();
        if (!exists.isEmpty()) {
            jdbcTemplate.update(
                """
                UPDATE crawl_chapter
                SET chapter_title = ?, content = ?, word_count = ?, crawl_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                chapterTitle,
                content,
                wordCount,
                exists.get(0)
            );
            return;
        }
        jdbcTemplate.update(
            """
            INSERT INTO crawl_chapter
            (platform, book_id, chapter_no, chapter_title, content, word_count, crawl_time, create_time, deleted)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """,
            platform,
            bookId,
            chapterNo,
            chapterTitle,
            content,
            wordCount
        );
    }

    public List<ChapterVO> findChapters(Long bookId, int limit) {
        return jdbcTemplate.query(
            """
            SELECT book_id, chapter_no, chapter_title, content, word_count
            FROM crawl_chapter
            WHERE book_id = ? AND deleted = 0
            ORDER BY chapter_no ASC
            LIMIT ?
            """,
            CHAPTER_ROW_MAPPER,
            bookId,
            limit
        );
    }
}

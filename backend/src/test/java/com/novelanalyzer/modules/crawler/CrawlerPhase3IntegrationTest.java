package com.novelanalyzer.modules.crawler;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:phase3db;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "app.security.rate-limit-per-minute=100"
    }
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class CrawlerPhase3IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PythonCrawlerClient pythonCrawlerClient;

    @Test
    void shouldCacheRankAndPersistData() throws Exception {
        when(pythonCrawlerClient.fetchRank(anyString(), anyString())).thenReturn(List.of(
            rankItem(1, "示例书1", "作者1", "https://fanqienovel.com/page/abc1"),
            rankItem(2, "示例书2", "作者2", "https://fanqienovel.com/page/abc2")
        ));

        String token = loginAndGetToken("admin", "admin123");
        String requestBody = "{\"platform\":\"fanqie\",\"category\":\"male-hot-a\"}";

        mockMvc.perform(post("/api/crawler/rank")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(post("/api/crawler/rank")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2));

        verify(pythonCrawlerClient, times(1)).fetchRank("fanqie", "male-hot-a");
        Integer rankCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_rank", Integer.class);
        Integer bookCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_book", Integer.class);
        assertThat(rankCount).isEqualTo(2);
        assertThat(bookCount).isEqualTo(2);
    }

    @Test
    void shouldFetchBookAndChapters() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        when(pythonCrawlerClient.fetchRank(anyString(), anyString())).thenReturn(List.of(
            rankItem(1, "示例书3", "作者3", "https://fanqienovel.com/page/abc3")
        ));
        when(pythonCrawlerClient.fetchBook(anyString(), anyString())).thenReturn(bookDetail("https://fanqienovel.com/page/abc3"));
        when(pythonCrawlerClient.fetchChapters(anyString(), anyString(), anyInt())).thenReturn(List.of(
            chapterItem(1, "第一章"),
            chapterItem(2, "第二章"),
            chapterItem(3, "第三章")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/rank")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"category\":\"male-hot-b\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        Long bookId = jdbcTemplate.queryForObject("SELECT id FROM crawl_book LIMIT 1", Long.class);
        assertThat(bookId).isNotNull();

        mockMvc.perform(get("/api/crawler/book/" + bookId)
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.bookId").value(bookId));

        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(3));

        Integer chapterCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_chapter", Integer.class);
        assertThat(chapterCount).isEqualTo(3);
    }

    @Test
    void shouldReuseLatestRankSnapshotWhenAutoRefreshStillValid() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        LocalDateTime snapshotTime = LocalDateTime.now().minusHours(12);
        long bookId1 = insertBook("fanqie", "db-book-1", "DB Book One", "DB Author One", "DB Intro One",
            "https://fanqienovel.com/page/db-book-1", snapshotTime);
        long bookId2 = insertBook("fanqie", "db-book-2", "DB Book Two", "DB Author Two", "DB Intro Two",
            "https://fanqienovel.com/page/db-book-2", snapshotTime);
        insertRankSnapshot("fanqie", "male-hot-a", snapshotTime, bookId1, "DB Book One",
            "https://fanqienovel.com/page/db-book-1", "DB Author One", "DB Intro One", 1);
        insertRankSnapshot("fanqie", "male-hot-a", snapshotTime, bookId2, "DB Book Two",
            "https://fanqienovel.com/page/db-book-2", "DB Author Two", "DB Intro Two", 2);

        when(pythonCrawlerClient.fetchRank("fanqie", "male-hot-a")).thenReturn(List.of(
            rankItem(1, "Crawler Book One", "Crawler Author", "https://fanqienovel.com/page/crawler-1")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/rank")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","category":"male-hot-a","refreshMode":"AUTO"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].bookName").value("DB Book One"));

        verify(pythonCrawlerClient, times(0)).fetchRank("fanqie", "male-hot-a");
    }

    @Test
    void shouldReturnDatabaseSnapshotWhenForceRefreshQuotaExceeded() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        LocalDateTime snapshotTime = LocalDateTime.now().minusHours(8);
        long bookId = insertBook("fanqie", "force-db-1", "Forced DB Book", "Forced DB Author", "Forced DB Intro",
            "https://fanqienovel.com/page/force-db-1", snapshotTime);
        insertRankSnapshot("fanqie", "male-hot-b", snapshotTime, bookId, "Forced DB Book",
            "https://fanqienovel.com/page/force-db-1", "Forced DB Author", "Forced DB Intro", 1);
        insertCrawlerTask("rank_refresh", "fanqie",
            "{\"platform\":\"fanqie\",\"category\":\"male-hot-b\",\"refreshMode\":\"FORCE\"}",
            2, LocalDateTime.now().minusHours(10), LocalDateTime.now().minusHours(10).plusMinutes(1));
        insertCrawlerTask("rank_refresh", "fanqie",
            "{\"platform\":\"fanqie\",\"category\":\"male-hot-b\",\"refreshMode\":\"FORCE\"}",
            2, LocalDateTime.now().minusHours(6), LocalDateTime.now().minusHours(6).plusMinutes(1));

        when(pythonCrawlerClient.fetchRank("fanqie", "male-hot-b")).thenReturn(List.of(
            rankItem(1, "Crawler Forced Book", "Crawler Author", "https://fanqienovel.com/page/crawler-force")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/rank")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","category":"male-hot-b","refreshMode":"FORCE","forceReason":"manual"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].bookName").value("Forced DB Book"));

        verify(pythonCrawlerClient, times(0)).fetchRank("fanqie", "male-hot-b");
    }

    @Test
    void shouldRepairBookLinkWhenStoredLinkInvalid() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        LocalDateTime lastCrawlTime = LocalDateTime.now().minusDays(10);
        long bookId = insertBook("fanqie", "123456", "Repair Target", "Repair Author", "Repair Intro",
            "https://fanqienovel.com/page/invalid-old", lastCrawlTime);

        when(pythonCrawlerClient.fetchBook("fanqie", "https://fanqienovel.com/page/invalid-old"))
            .thenThrow(new RuntimeException("invalid link"));
        when(pythonCrawlerClient.fetchBook("fanqie", "https://fanqienovel.com/page/123456"))
            .thenReturn(bookDetail("https://fanqienovel.com/page/123456"));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(get("/api/crawler/book/" + bookId)
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.bookUrl").value("https://fanqienovel.com/page/123456"));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private ExternalRankItem rankItem(int rankNo, String bookName, String author, String url) {
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(rankNo);
        item.setBookName(bookName);
        item.setAuthor(author);
        item.setIntro("intro-" + bookName);
        item.setBookUrl(url);
        item.setPlatformBookId("pid-" + rankNo);
        return item;
    }

    private ExternalBookDetail bookDetail(String url) {
        ExternalBookDetail detail = new ExternalBookDetail();
        detail.setBookName("详情书名");
        detail.setAuthor("详情作者");
        detail.setIntro("详情简介");
        detail.setBookUrl(url);
        detail.setPlatformBookId("pid-detail");
        return detail;
    }

    private ExternalChapterItem chapterItem(int no, String title) {
        ExternalChapterItem item = new ExternalChapterItem();
        item.setChapterNo(no);
        item.setChapterTitle(title);
        item.setContent(title + " 内容");
        return item;
    }
    private void insertSystemConfig(String key, String value) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS system_config (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                config_key VARCHAR(100) NOT NULL,
                config_value CLOB,
                config_type VARCHAR(50),
                description VARCHAR(200),
                is_editable TINYINT DEFAULT 1,
                create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                deleted TINYINT DEFAULT 0
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO system_config(config_key, config_value, config_type, description, is_editable, deleted) VALUES (?, ?, ?, ?, ?, ?)",
            key, value, "crawler", key, 1, 0
        );
    }

    private long insertBook(String platform,
                            String platformBookId,
                            String bookName,
                            String author,
                            String intro,
                            String bookUrl,
                            LocalDateTime lastCrawlTime) {
        jdbcTemplate.update(
            "INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            platform,
            platformBookId,
            bookName,
            author,
            intro,
            bookUrl,
            Timestamp.valueOf(lastCrawlTime),
            Timestamp.valueOf(lastCrawlTime),
            Timestamp.valueOf(lastCrawlTime),
            0
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM crawl_book WHERE platform = ? AND book_url = ?",
            Long.class,
            platform,
            bookUrl
        );
        assertThat(id).isNotNull();
        return id;
    }

    private void insertRankSnapshot(String platform,
                                    String category,
                                    LocalDateTime crawlTime,
                                    long bookId,
                                    String bookName,
                                    String bookUrl,
                                    String author,
                                    String intro,
                                    int rankNo) {
        jdbcTemplate.update(
            "INSERT INTO crawl_rank(platform, category, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            platform,
            category,
            rankNo,
            bookId,
            bookName,
            bookUrl,
            author,
            intro,
            Timestamp.valueOf(crawlTime),
            Timestamp.valueOf(crawlTime),
            0
        );
    }

    private void insertCrawlerTask(String taskType,
                                   String platform,
                                   String requestJson,
                                   int status,
                                   LocalDateTime startTime,
                                   LocalDateTime endTime) {
        jdbcTemplate.update(
            "INSERT INTO crawler_task(task_type, platform, request_json, status, start_time, end_time, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            taskType,
            platform,
            requestJson,
            status,
            Timestamp.valueOf(startTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime),
            Timestamp.valueOf(endTime)
        );
    }
}

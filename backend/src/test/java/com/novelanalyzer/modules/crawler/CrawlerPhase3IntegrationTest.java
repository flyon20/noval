package com.novelanalyzer.modules.crawler;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankBoard;
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
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
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
    void shouldSyncBoardCatalogFromCrawler() throws Exception {
        when(pythonCrawlerClient.fetchBoardCatalog("fanqie", 20)).thenReturn(List.of(
            boardItem("fanqie", "male-new", "男频新书榜", "urban-brain", "都市脑洞"),
            boardItem("fanqie", "male-read", "男频阅读榜", "urban-power", "都市高武")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(get("/api/crawler/boards")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].channelCode").value("male-new"))
            .andExpect(jsonPath("$.data[0].channelName").value("男频新书榜"))
            .andExpect(jsonPath("$.data[0].boards[0].boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.data[0].boards[0].boardName").value("都市脑洞"));

        Integer boardCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM rank_board WHERE platform = ? AND deleted = 0",
            Integer.class,
            "fanqie"
        );
        assertThat(boardCount).isEqualTo(2);
    }

    @Test
    void shouldSaveAndReturnUserRankPreference() throws Exception {
        String token = loginAndGetToken("writer", "writer123");

        mockMvc.perform(post("/api/crawler/preference")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.platform").value("fanqie"))
            .andExpect(jsonPath("$.data.channelCode").value("male-new"))
            .andExpect(jsonPath("$.data.boardCode").value("urban-brain"));

        mockMvc.perform(get("/api/crawler/preference")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.userId").value(2))
            .andExpect(jsonPath("$.data.channelCode").value("male-new"))
            .andExpect(jsonPath("$.data.boardCode").value("urban-brain"));
    }

    @Test
    void shouldCacheRankAndPersistData() throws Exception {
        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyInt())).thenReturn(List.of(
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

        verify(pythonCrawlerClient, times(1)).fetchRank("fanqie", "male-hot-a", 20);
        Integer rankCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_rank", Integer.class);
        Integer bookCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM crawl_book", Integer.class);
        assertThat(rankCount).isEqualTo(2);
        assertThat(bookCount).isEqualTo(2);
    }

    @Test
    void shouldFetchBookAndChapters() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyInt())).thenReturn(List.of(
            rankItem(1, "示例书3", "作者3", "https://fanqienovel.com/page/abc3")
        ));
        when(pythonCrawlerClient.fetchBook(anyString(), anyString(), anyInt())).thenReturn(bookDetail("https://fanqienovel.com/page/abc3"));
        when(pythonCrawlerClient.fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(
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
    void shouldRefreshWholeBoardAndPageSnapshotWithoutRecrawling() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain", 20)).thenReturn(List.of(
            rankItem(1, "Book 01", "Author 01", "https://fanqienovel.com/page/board-01"),
            rankItem(2, "Book 02", "Author 02", "https://fanqienovel.com/page/board-02"),
            rankItem(3, "Book 03", "Author 03", "https://fanqienovel.com/page/board-03"),
            rankItem(4, "Book 04", "Author 04", "https://fanqienovel.com/page/board-04"),
            rankItem(5, "Book 05", "Author 05", "https://fanqienovel.com/page/board-05"),
            rankItem(6, "Book 06", "Author 06", "https://fanqienovel.com/page/board-06"),
            rankItem(7, "Book 07", "Author 07", "https://fanqienovel.com/page/board-07"),
            rankItem(8, "Book 08", "Author 08", "https://fanqienovel.com/page/board-08"),
            rankItem(9, "Book 09", "Author 09", "https://fanqienovel.com/page/board-09"),
            rankItem(10, "Book 10", "Author 10", "https://fanqienovel.com/page/board-10"),
            rankItem(11, "Book 11", "Author 11", "https://fanqienovel.com/page/board-11"),
            rankItem(12, "Book 12", "Author 12", "https://fanqienovel.com/page/board-12")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/rank/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"FORCE"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.snapshotId").isNumber())
            .andExpect(jsonPath("$.data.total").value(12))
            .andExpect(jsonPath("$.data.reused").value(false));

        mockMvc.perform(get("/api/crawler/rank/page")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain")
                .param("page", "2")
                .param("pageSize", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(5))
            .andExpect(jsonPath("$.data.total").value(12))
            .andExpect(jsonPath("$.data.items.length()").value(5))
            .andExpect(jsonPath("$.data.items[0].rankNo").value(6))
            .andExpect(jsonPath("$.data.items[0].bookName").value("Book 06"));

        mockMvc.perform(post("/api/crawler/rank/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain","refreshMode":"AUTO"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(12))
            .andExpect(jsonPath("$.data.reused").value(true));

        verify(pythonCrawlerClient, times(1)).fetchRank("fanqie", "male-new", "urban-brain", 20);
    }

    @Test
    void shouldReturnDatabaseSnapshotWhenForceRefreshQuotaExceeded() throws Exception {
        insertSystemConfig("crawler.rank.refresh-days", "5");
        insertSystemConfig("crawler.rank.force-cooldown-days", "2");
        insertSystemConfig("crawler.rank.force-max-times", "2");

        LocalDateTime snapshotTime = LocalDateTime.now().minusHours(8);
        long bookId = insertBook("fanqie", "force-db-1", "Forced DB Book", "Forced DB Author", "Forced DB Intro",
            "https://fanqienovel.com/page/force-db-1", snapshotTime);
        long boardId = insertRankBoard("fanqie", "male-read", "男频阅读榜", "urban-power", "都市高武");
        long snapshotId = insertBoardSnapshot(boardId, snapshotTime, 1);
        insertRankSnapshot("fanqie", "male-hot-b", "male-read", "urban-power", snapshotId, snapshotTime, bookId,
            "Forced DB Book", "https://fanqienovel.com/page/force-db-1", "Forced DB Author", "Forced DB Intro", 1);
        insertCrawlerTask("rank_refresh", "fanqie",
            "{\"platform\":\"fanqie\",\"channelCode\":\"male-read\",\"boardCode\":\"urban-power\",\"refreshMode\":\"FORCE\"}",
            2, LocalDateTime.now().minusHours(10), LocalDateTime.now().minusHours(10).plusMinutes(1));
        insertCrawlerTask("rank_refresh", "fanqie",
            "{\"platform\":\"fanqie\",\"channelCode\":\"male-read\",\"boardCode\":\"urban-power\",\"refreshMode\":\"FORCE\"}",
            2, LocalDateTime.now().minusHours(6), LocalDateTime.now().minusHours(6).plusMinutes(1));

        when(pythonCrawlerClient.fetchRank("fanqie", "male-read", "urban-power", 20)).thenReturn(List.of(
            rankItem(1, "Crawler Forced Book", "Crawler Author", "https://fanqienovel.com/page/crawler-force")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/rank/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-read","boardCode":"urban-power","refreshMode":"FORCE","forceReason":"manual"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.reused").value(true))
            .andExpect(jsonPath("$.data.refreshLimited").value(true));

        verify(pythonCrawlerClient, times(0)).fetchRank("fanqie", "male-read", "urban-power", 20);
    }

    @Test
    void shouldRepairBookLinkWhenStoredLinkInvalid() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        LocalDateTime lastCrawlTime = LocalDateTime.now().minusDays(10);
        long bookId = insertBook("fanqie", "123456", "Repair Target", "Repair Author", "Repair Intro",
            "https://fanqienovel.com/page/invalid-old", lastCrawlTime);

        when(pythonCrawlerClient.fetchBook("fanqie", "https://fanqienovel.com/page/invalid-old", 20))
            .thenThrow(new RuntimeException("invalid link"));
        when(pythonCrawlerClient.fetchBook("fanqie", "https://fanqienovel.com/page/123456", 20))
            .thenReturn(bookDetail("https://fanqienovel.com/page/123456"));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(get("/api/crawler/book/" + bookId)
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.bookUrl").value("https://fanqienovel.com/page/123456"));
    }

    @Test
    void shouldRefetchChaptersWhenPersistedChaptersAreStaleDescendingSnapshot() throws Exception {
        insertSystemConfig("crawler.book.refresh-days", "7");

        LocalDateTime lastCrawlTime = LocalDateTime.now().minusHours(2);
        long bookId = insertBook(
            "fanqie",
            "repair-chapter-1",
            "Repair Chapter Target",
            "Repair Author",
            "Repair Intro",
            "https://fanqienovel.com/page/repair-chapter-1",
            lastCrawlTime
        );
        insertChapter(bookId, 1, "第128章 潜入", "old chapter 128", lastCrawlTime);
        insertChapter(bookId, 2, "第127章 计策", "old chapter 127", lastCrawlTime);
        insertChapter(bookId, 3, "第126章 粮草大营", "old chapter 126", lastCrawlTime);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/repair-chapter-1", 3, 1, 20, 3))
            .thenReturn(List.of(
                chapterItem(1, "第1章 开局"),
                chapterItem(2, "第2章 相遇"),
                chapterItem(3, "第3章 启程")
            ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("第1章 开局"))
            .andExpect(jsonPath("$.data[2].chapterTitle").value("第3章 启程"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/repair-chapter-1", 3, 1, 20, 3);
    }

    @Test
    void shouldReuseStoredPrefixChaptersWithoutRecrawlingForSmallerRequest() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "prefix-reuse-1",
            "Prefix Reuse Book",
            "Reuse Author",
            "Reuse Intro",
            "https://fanqienovel.com/page/prefix-reuse-1",
            crawlTime
        );
        insertChapter(bookId, 1, "第1章 开局", "chapter 1", crawlTime);
        insertChapter(bookId, 2, "第2章 相遇", "chapter 2", crawlTime);
        insertChapter(bookId, 3, "第3章 启程", "chapter 3", crawlTime);
        insertChapter(bookId, 4, "第4章 破局", "chapter 4", crawlTime);
        insertChapter(bookId, 5, "第5章 收束", "chapter 5", crawlTime);

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("第1章 开局"))
            .andExpect(jsonPath("$.data[2].chapterTitle").value("第3章 启程"));

        verify(pythonCrawlerClient, times(0))
            .fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void shouldOnlyFetchMissingChapterRangeWhenExtendingStoredPrefix() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "prefix-extend-1",
            "Prefix Extend Book",
            "Extend Author",
            "Extend Intro",
            "https://fanqienovel.com/page/prefix-extend-1",
            crawlTime
        );
        insertChapter(bookId, 1, "第1章 开局", "chapter 1", crawlTime);
        insertChapter(bookId, 2, "第2章 相遇", "chapter 2", crawlTime);
        insertChapter(bookId, 3, "第3章 启程", "chapter 3", crawlTime);
        insertChapter(bookId, 4, "第4章 破局", "chapter 4", crawlTime);
        insertChapter(bookId, 5, "第5章 收束", "chapter 5", crawlTime);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/prefix-extend-1", 5, 6, 20, 3))
            .thenReturn(List.of(
                chapterItem(6, "第6章 转折"),
                chapterItem(7, "第7章 深入"),
                chapterItem(8, "第8章 对峙"),
                chapterItem(9, "第9章 反转"),
                chapterItem(10, "第10章 定局")
            ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":10}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(10))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("第1章 开局"))
            .andExpect(jsonPath("$.data[9].chapterTitle").value("第10章 定局"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/prefix-extend-1", 5, 6, 20, 3);
    }

    @Test
    void shouldRepairLegacyChaptersWithoutSourceWordCount() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "legacy-chapter-1",
            "Legacy Chapter Book",
            "Legacy Author",
            "Legacy Intro",
            "https://fanqienovel.com/page/legacy-chapter-1",
            crawlTime
        );
        insertChapter(bookId, 1, "Chapter 1", "legacy truncated chapter", crawlTime, null);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/legacy-chapter-1", 1, 1, 20, 3))
            .thenReturn(List.of(chapterItem(1, "Chapter 1 repaired", 2452)));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("Chapter 1 repaired"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/legacy-chapter-1", 1, 1, 20, 3);
    }

    @Test
    void shouldRepairPersistedChaptersWhenStoredContentIsShorterThanSourceWordCount() throws Exception {
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "short-chapter-1",
            "Short Chapter Book",
            "Short Author",
            "Short Intro",
            "https://fanqienovel.com/page/short-chapter-1",
            crawlTime
        );
        insertChapter(bookId, 1, "Chapter 1", "short content", crawlTime, 2452);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/short-chapter-1", 1, 1, 20, 3))
            .thenReturn(List.of(chapterItem(1, "Chapter 1 repaired", 2452)));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].chapterTitle").value("Chapter 1 repaired"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/short-chapter-1", 1, 1, 20, 3);
    }

    @Test
    void shouldForceRefreshChaptersAndReturnUsageStatsForNormalUser() throws Exception {
        insertSystemConfig("crawler.chapter.force-refresh.user-max-times", "3");
        insertSystemConfig("crawler.rank.refresh-days", "5");
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "force-refresh-user-1",
            "Force Refresh User Book",
            "User Author",
            "User Intro",
            "https://fanqienovel.com/page/force-refresh-user-1",
            crawlTime
        );
        insertChapter(bookId, 1, "第1章 旧内容", "old chapter 1", crawlTime);
        insertChapter(bookId, 2, "第2章 旧内容", "old chapter 2", crawlTime);
        insertChapter(bookId, 3, "第3章 旧内容", "old chapter 3", crawlTime);

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/force-refresh-user-1", 3, 1, 20, 3))
            .thenReturn(List.of(
                chapterItem(1, "第1章 新内容"),
                chapterItem(2, "第2章 新内容"),
                chapterItem(3, "第3章 新内容")
            ));

        String token = loginAndGetToken("writer", "writer123");
        mockMvc.perform(post("/api/crawler/chapters/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.maxAllowedRefreshTimes").value(3))
            .andExpect(jsonPath("$.data.usedRefreshTimes").value(1))
            .andExpect(jsonPath("$.data.remainingRefreshTimes").value(2))
            .andExpect(jsonPath("$.data.windowDays").value(5))
            .andExpect(jsonPath("$.data.chapters[0].chapterTitle").value("第1章 新内容"))
            .andExpect(jsonPath("$.data.chapters[2].chapterTitle").value("第3章 新内容"));

        verify(pythonCrawlerClient, times(1))
            .fetchChapters("fanqie", "https://fanqienovel.com/page/force-refresh-user-1", 3, 1, 20, 3);
    }

    @Test
    void shouldRejectForceRefreshWhenNormalUserExceedsConfiguredLimit() throws Exception {
        insertSystemConfig("crawler.chapter.force-refresh.user-max-times", "1");
        insertSystemConfig("crawler.rank.refresh-days", "5");
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "force-refresh-limit-1",
            "Force Refresh Limit Book",
            "Limit Author",
            "Limit Intro",
            "https://fanqienovel.com/page/force-refresh-limit-1",
            crawlTime
        );
        insertCrawlerTask(
            "chapter_force_refresh",
            "fanqie",
            "{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3,\"userId\":2,\"username\":\"writer\"}",
            2,
            LocalDateTime.now().minusHours(2),
            LocalDateTime.now().minusHours(2).plusMinutes(1)
        );

        String token = loginAndGetToken("writer", "writer123");
        mockMvc.perform(post("/api/crawler/chapters/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":3}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));

        verify(pythonCrawlerClient, times(0))
            .fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void shouldUseFixedAdminRefreshLimitOfTwenty() throws Exception {
        insertSystemConfig("crawler.chapter.force-refresh.user-max-times", "1");
        insertSystemConfig("crawler.rank.refresh-days", "5");
        LocalDateTime crawlTime = LocalDateTime.now().minusHours(1);
        long bookId = insertBook(
            "fanqie",
            "force-refresh-admin-1",
            "Force Refresh Admin Book",
            "Admin Author",
            "Admin Intro",
            "https://fanqienovel.com/page/force-refresh-admin-1",
            crawlTime
        );

        when(pythonCrawlerClient.fetchChapters("fanqie", "https://fanqienovel.com/page/force-refresh-admin-1", 1, 1, 20, 3))
            .thenReturn(List.of(chapterItem(1, "第1章 管理员刷新")));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/crawler/chapters/refresh")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":" + bookId + ",\"chapterCount\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.maxAllowedRefreshTimes").value(20))
            .andExpect(jsonPath("$.data.usedRefreshTimes").value(1))
            .andExpect(jsonPath("$.data.remainingRefreshTimes").value(19))
            .andExpect(jsonPath("$.data.chapters[0].chapterTitle").value("第1章 管理员刷新"));
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
        return chapterItem(no, title, null);
    }

    private ExternalChapterItem chapterItem(int no, String title, Integer sourceWordCount) {
        ExternalChapterItem item = new ExternalChapterItem();
        item.setChapterNo(no);
        item.setChapterTitle(title);
        item.setContent(title + " 内容");
        item.setSourceWordCount(sourceWordCount);
        return item;
    }
    private ExternalRankBoard boardItem(String platform,
                                        String channelCode,
                                        String channelName,
                                        String boardCode,
                                        String boardName) {
        ExternalRankBoard item = new ExternalRankBoard();
        item.setPlatform(platform);
        item.setChannelCode(channelCode);
        item.setChannelName(channelName);
        item.setBoardCode(boardCode);
        item.setBoardName(boardName);
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

    private long insertRankBoard(String platform,
                                 String channelCode,
                                 String channelName,
                                 String boardCode,
                                 String boardName) {
        jdbcTemplate.update(
            "INSERT INTO rank_board(platform, channel_code, board_code, board_name, description, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            platform,
            channelCode,
            boardCode,
            boardName,
            channelName,
            Timestamp.valueOf(LocalDateTime.now()),
            Timestamp.valueOf(LocalDateTime.now()),
            0
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM rank_board WHERE platform = ? AND channel_code = ? AND board_code = ? AND deleted = 0",
            Long.class,
            platform,
            channelCode,
            boardCode
        );
        assertThat(id).isNotNull();
        return id;
    }

    private long insertBoardSnapshot(long rankBoardId, LocalDateTime snapshotTime, int recordCount) {
        jdbcTemplate.update(
            "INSERT INTO rank_snapshot(rank_board_id, snapshot_time, record_count, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?)",
            rankBoardId,
            Timestamp.valueOf(snapshotTime),
            recordCount,
            Timestamp.valueOf(snapshotTime),
            Timestamp.valueOf(snapshotTime),
            0
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM rank_snapshot WHERE rank_board_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
            Long.class,
            rankBoardId
        );
        assertThat(id).isNotNull();
        return id;
    }

    private void insertRankSnapshot(String platform,
                                    String category,
                                    String channelCode,
                                    String boardCode,
                                    Long snapshotId,
                                    LocalDateTime crawlTime,
                                    long bookId,
                                    String bookName,
                                    String bookUrl,
                                    String author,
                                    String intro,
                                    int rankNo) {
        jdbcTemplate.update(
            "INSERT INTO crawl_rank(platform, category, channel_code, board_code, snapshot_id, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            platform,
            category,
            channelCode,
            boardCode,
            snapshotId,
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

    private void insertChapter(long bookId,
                               int chapterNo,
                               String chapterTitle,
                               String content,
                               LocalDateTime crawlTime) {
        insertChapter(bookId, chapterNo, chapterTitle, content, crawlTime, content.length());
    }

    private void insertChapter(long bookId,
                               int chapterNo,
                               String chapterTitle,
                               String content,
                               LocalDateTime crawlTime,
                               Integer sourceWordCount) {
        jdbcTemplate.update(
            "INSERT INTO crawl_chapter(platform, book_id, chapter_no, chapter_title, content, word_count, source_word_count, crawl_time, create_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "fanqie",
            bookId,
            chapterNo,
            chapterTitle,
            content,
            content.length(),
            sourceWordCount,
            Timestamp.valueOf(crawlTime),
            Timestamp.valueOf(crawlTime),
            0
        );
    }
}

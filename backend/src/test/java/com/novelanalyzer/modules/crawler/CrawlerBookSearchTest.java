package com.novelanalyzer.modules.crawler;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookSearchItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:crawlerbooksearchdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=15",
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
class CrawlerBookSearchTest {

    private static final String ADMIN_PHONE = "15599316908";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private PythonCrawlerClient pythonCrawlerClient;

    @BeforeEach
    void prepareState() {
        jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = 1", ADMIN_PHONE);
        RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldReturnLocalBookCandidatesWithoutCallingCrawlerWhenEnough() throws Exception {
        insertBook("fanqie", "101", "Book Alpha", "Author A", "Intro A", "https://fanqienovel.com/page/101");

        String token = loginAndGetToken("admin123");
        mockMvc.perform(post("/api/crawler/books/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","keyword":"Book","limit":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].bookId").isNumber())
            .andExpect(jsonPath("$.data[0].bookName").value("Book Alpha"))
            .andExpect(jsonPath("$.data[0].platformBookId").value("101"));

        verify(pythonCrawlerClient, never()).searchBooks("fanqie", "Book", 1);
    }

    @Test
    void shouldCallCrawlerWhenLocalCandidatesAreInsufficient() throws Exception {
        insertBook("fanqie", "101", "Book Alpha", "Author A", "Intro A", "https://fanqienovel.com/page/101");
        when(pythonCrawlerClient.searchBooks("fanqie", "Book", 3)).thenReturn(List.of(
            searchItem("102", "Book Beta", "Author B", "Intro B", "https://fanqienovel.com/page/102"),
            searchItem("103", "Book Gamma", "Author C", "Intro C", "https://fanqienovel.com/page/103")
        ));

        String token = loginAndGetToken("admin123");
        mockMvc.perform(post("/api/crawler/books/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","keyword":"Book","limit":3}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].bookName").value("Book Alpha"))
            .andExpect(jsonPath("$.data[1].bookId").doesNotExist())
            .andExpect(jsonPath("$.data[1].bookName").value("Book Beta"))
            .andExpect(jsonPath("$.data[2].bookName").value("Book Gamma"));

        verify(pythonCrawlerClient).searchBooks("fanqie", "Book", 3);
    }

    @Test
    void shouldRejectBlankKeyword() throws Exception {
        String token = loginAndGetToken("admin123");
        mockMvc.perform(post("/api/crawler/books/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","keyword":"   ","limit":3}
                    """))
            .andExpect(status().isBadRequest());
    }

    private String loginAndGetToken(String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + ADMIN_PHONE + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private void insertBook(String platform,
                            String platformBookId,
                            String bookName,
                            String author,
                            String intro,
                            String bookUrl) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, last_crawl_time, create_time, update_time, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            platform, platformBookId, bookName, author, intro, bookUrl,
            Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now), 0
        );
    }

    private ExternalBookSearchItem searchItem(String platformBookId,
                                              String bookName,
                                              String author,
                                              String intro,
                                              String bookUrl) {
        ExternalBookSearchItem item = new ExternalBookSearchItem();
        item.setPlatformBookId(platformBookId);
        item.setBookName(bookName);
        item.setAuthor(author);
        item.setIntro(intro);
        item.setBookUrl(bookUrl);
        return item;
    }
}
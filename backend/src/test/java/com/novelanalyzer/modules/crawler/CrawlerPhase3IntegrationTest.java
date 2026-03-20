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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
}


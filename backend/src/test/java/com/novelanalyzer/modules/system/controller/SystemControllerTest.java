package com.novelanalyzer.modules.system.controller;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankBoard;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "app.security.rate-limit-per-minute=100",
        "app.security.protected-path-prefixes=/api/auth,/api/secure,/api/system,/api/crawler,/api/analysis,/api/config,/api/data",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    }
)
@AutoConfigureMockMvc
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
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PythonCrawlerClient pythonCrawlerClient;

    @Test
    void shouldReturnHealthWithUnifiedResultAndTraceId() throws Exception {
        mockMvc.perform(get("/api/system/health"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldReuseIncomingTraceId() throws Exception {
        String traceId = "trace-test-001";
        mockMvc.perform(get("/api/system/health").header("X-Trace-Id", traceId))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", traceId))
            .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void shouldBootstrapRankBoardsOnLogin() throws Exception {
        when(pythonCrawlerClient.fetchBoardCatalog("fanqie")).thenReturn(List.of(
            boardItem("fanqie", "male-new", "男频新书榜", "urban-brain", "都市脑洞")
        ));
        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain")).thenReturn(List.of(
            rankItem(1, "Bootstrap Book 01", "Author 01", "https://fanqienovel.com/page/bootstrap-01"),
            rankItem(2, "Bootstrap Book 02", "Author 02", "https://fanqienovel.com/page/bootstrap-02")
        ));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/system/login-bootstrap")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.results.length()").value(1))
            .andExpect(jsonPath("$.data.results[0].channelCode").value("male-new"))
            .andExpect(jsonPath("$.data.results[0].boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.data.results[0].total").value(2));
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

    private ExternalRankItem rankItem(int rankNo, String bookName, String author, String url) {
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(rankNo);
        item.setBookName(bookName);
        item.setAuthor(author);
        item.setIntro("intro-" + bookName);
        item.setBookUrl(url);
        item.setPlatformBookId("bootstrap-" + rankNo);
        return item;
    }
}

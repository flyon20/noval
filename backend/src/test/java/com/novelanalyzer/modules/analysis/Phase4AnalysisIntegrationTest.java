package com.novelanalyzer.modules.analysis;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:phase4db;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql",
        "classpath:sql/phase4-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class Phase4AnalysisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldAnalyzeAndPersistResult() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":1001,\"chapterCount\":3}"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisType").value("deconstruct"))
            .andExpect(jsonPath("$.data.resultJson.analysisType").value("deconstruct"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM analysis_result WHERE analysis_type='deconstruct'",
            Integer.class
        );
        assertThat(count).isNotNull();
        assertThat(count).isGreaterThan(0);
        String resultJson = jdbcTemplate.queryForObject(
            "SELECT result_json FROM analysis_result WHERE analysis_type='deconstruct' ORDER BY id DESC LIMIT 1",
            String.class
        );
        assertThat(resultJson).contains("\"analysisType\":\"deconstruct\"");
    }

    @Test
    void shouldUpdateAndGetPromptConfig() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promptType\":\"deconstruct\",\"promptName\":\"default-deconstruct\",\"promptContent\":\"UPDATED {{content}}\",\"modelName\":\"dify\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .param("promptType", "deconstruct"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.promptContent").value("UPDATED {{content}}"));
    }

    @Test
    void shouldAllowUserRoleToUpdatePromptConfig() throws Exception {
        String token = loginAndGetToken("writer", "writer123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promptType\":\"deconstruct\",\"promptName\":\"default-deconstruct\",\"promptContent\":\"USER-EDIT {{content}}\",\"modelName\":\"dify\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.promptContent").value("USER-EDIT {{content}}"));
    }

    @Test
    void shouldRejectPromptConfigWithoutContentPlaceholder() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promptType\":\"deconstruct\",\"promptName\":\"default-deconstruct\",\"promptContent\":\"MISSING-PLACEHOLDER\",\"modelName\":\"dify\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("promptContent must contain {{content}} placeholder"));
    }

    @Test
    void shouldRefreshAnalysisResultAfterPromptUpdateForSameRequest() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        updatePromptConfig(token, "PROMPT-V1 {{content}}");

        MvcResult firstResult = analyzeDeconstruct(token);
        String firstResponse = firstResult.getResponse().getContentAsString();
        Number firstId = JsonPath.read(firstResponse, "$.data.id");
        String firstContent = JsonPath.read(firstResponse, "$.data.resultContent");
        assertThat(firstContent).contains("PROMPT-V1");

        updatePromptConfig(token, "PROMPT-V2 {{content}}");

        MvcResult secondResult = analyzeDeconstruct(token);
        String secondResponse = secondResult.getResponse().getContentAsString();
        Number secondId = JsonPath.read(secondResponse, "$.data.id");
        String secondContent = JsonPath.read(secondResponse, "$.data.resultContent");

        assertThat(secondContent).contains("PROMPT-V2");
        assertThat(secondId.longValue()).isNotEqualTo(firstId.longValue());
    }

    @Test
    void shouldReanalyzeWhenForceReanalyzeEnabled() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        MvcResult firstResult = mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        Number firstId = JsonPath.read(firstResult.getResponse().getContentAsString(), "$.data.id");

        MvcResult secondResult = mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        Number secondId = JsonPath.read(secondResult.getResponse().getContentAsString(), "$.data.id");

        assertThat(secondId.longValue()).isNotEqualTo(firstId.longValue());
    }

    @Test
    void shouldStreamDeconstructAnalysisWithSseProtocol() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        MvcResult streamStart = mockMvc.perform(post("/api/analysis/deconstruct/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2}
                    """))
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        streamStart.getAsyncResult(10000);
        String body = streamStart.getResponse().getContentAsString();
        assertThat(body).containsPattern("(?s)event:\\s*start.*event:\\s*delta.*event:\\s*done");
        assertThat(body).contains("\"event\":\"start\"");
        assertThat(body).contains("\"analysisType\":\"deconstruct\"");
        assertThat(body).contains("\"event\":\"done\"");
        assertThat(body).contains("\"resultContent\"");
    }

    @Test
    void shouldStreamTrendAnalysisWithSseProtocol() throws Exception {
        insertThemePromptConfig();
        insertRankSnapshot("fanqie", "male-hot-a", 1, 1001L, "Book One", "Author One", "Intro One",
            LocalDateTime.of(2026, 3, 18, 10, 0));
        insertRankSnapshot("fanqie", "male-hot-a", 1, 1001L, "Book One", "Author One", "Intro One",
            LocalDateTime.of(2026, 3, 19, 10, 0));
        insertRankSnapshot("fanqie", "male-hot-a", 1, 1001L, "Book One", "Author One", "Intro One",
            LocalDateTime.of(2026, 3, 20, 10, 0));

        String token = loginAndGetToken("admin", "admin123");

        MvcResult streamStart = mockMvc.perform(post("/api/analysis/trend/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","category":"male-hot-a"}
                    """))
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        streamStart.getAsyncResult(10000);
        String body = streamStart.getResponse().getContentAsString();
        assertThat(body).containsPattern("(?s)event:\\s*start.*event:\\s*delta.*event:\\s*done");
        assertThat(body).contains("\"event\":\"start\"");
        assertThat(body).contains("\"analysisType\":\"theme\"");
        assertThat(body).contains("\"event\":\"done\"");
        assertThat(body).contains("\"sourceSnapshotCount\"");
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

    private void updatePromptConfig(String token, String promptContent) throws Exception {
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promptType\":\"deconstruct\",\"promptName\":\"default-deconstruct\",\"promptContent\":\""
                    + promptContent + "\",\"modelName\":\"dify\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    private MvcResult analyzeDeconstruct(String token) throws Exception {
        return mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"fanqie\",\"bookId\":1001,\"chapterCount\":2}"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
    }

    private void insertThemePromptConfig() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM prompt_config WHERE prompt_type = 'theme' AND deleted = 0",
            Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
            """
                INSERT INTO prompt_config(id, prompt_type, prompt_name, prompt_content, model_name, status, is_default, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            4L,
            "theme",
            "default-theme",
            "请对最近榜单快照进行题材趋势分析：{{content}}",
            "dify",
            1,
            1,
            0
        );
    }

    private void insertRankSnapshot(String platform,
                                    String category,
                                    int rankNo,
                                    Long bookId,
                                    String bookName,
                                    String author,
                                    String intro,
                                    LocalDateTime crawlTime) {
        jdbcTemplate.update(
            """
                INSERT INTO crawl_rank(platform, category, rank_no, book_id, book_name, book_url, author, intro, crawl_time, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            platform,
            category,
            rankNo,
            bookId,
            bookName,
            "https://fanqienovel.com/page/" + bookId,
            author,
            intro,
            Timestamp.valueOf(crawlTime),
            Timestamp.valueOf(crawlTime),
            0
        );
    }
}

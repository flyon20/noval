package com.novelanalyzer.modules.analysis;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.analysis.service.AiGatewayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        "app.security.rate-limit-per-minute=100",
        "app.ai.openai-compatible.api-key-ref=TEST_DEEPSEEK_API_KEY",
        "app.ai.openai-compatible.default-model=deepseek-chat",
        "app.ai.openai-compatible.streaming-enabled=true"
    }
)
@AutoConfigureMockMvc
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql",
        "classpath:sql/phase4-data-h2.sql",
        "classpath:sql/phase5-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class Phase4AnalysisIntegrationTest {

    private static final HttpServer MOCK_OPENAI_SERVER = startMockOpenAiServer();
    private static final HttpServer MOCK_LANGGRAPH_SERVER = startMockLangGraphServer();
    private static final AtomicReference<String> LAST_OPENAI_REQUEST_BODY = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_OPENAI_AUTHORIZATION = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_LANGGRAPH_REQUEST_BODY = new AtomicReference<>("");
    private static final AtomicInteger OPENAI_REQUEST_COUNT = new AtomicInteger();
    private static final AtomicInteger LANGGRAPH_REQUEST_COUNT = new AtomicInteger();

    static {
        System.setProperty("TEST_DEEPSEEK_API_KEY", "test-key");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.novelanalyzer.modules.crawler.service.CrawlerCacheService crawlerCacheService;

    @Autowired
    private AiGatewayService aiGatewayService;

    @Autowired
    private AiProperties aiProperties;

    @DynamicPropertySource
    static void registerAiProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.openai-compatible.base-url", () -> "http://127.0.0.1:" + MOCK_OPENAI_SERVER.getAddress().getPort() + "/v1");
        registry.add("app.ai.langgraph-worker.base-url", () -> "http://127.0.0.1:" + MOCK_LANGGRAPH_SERVER.getAddress().getPort());
        registry.add("app.ai.langgraph-worker.internal-api-key", () -> "test-langgraph-key");
    }

    @AfterAll
    static void shutdownMockServer() {
        MOCK_OPENAI_SERVER.stop(0);
        MOCK_LANGGRAPH_SERVER.stop(0);
        System.clearProperty("TEST_DEEPSEEK_API_KEY");
    }

    @BeforeEach
    void resetMockOpenAiCapture() {
        LAST_OPENAI_REQUEST_BODY.set("");
        LAST_OPENAI_AUTHORIZATION.set("");
        OPENAI_REQUEST_COUNT.set(0);
        LAST_LANGGRAPH_REQUEST_BODY.set("");
        LANGGRAPH_REQUEST_COUNT.set(0);
    }

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
    void shouldUpdateAndGetPromptConfigWithModelParameters() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"promptType":"deconstruct","promptName":"default-deconstruct","promptContent":"UPDATED {{content}}","modelName":"deepseek-chat","temperature":0.55,"maxTokens":1200}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.modelName").value("deepseek-chat"))
            .andExpect(jsonPath("$.data.temperature").value(0.55))
            .andExpect(jsonPath("$.data.maxTokens").value(1200));

        mockMvc.perform(get("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .param("promptType", "deconstruct"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.modelName").value("deepseek-chat"))
            .andExpect(jsonPath("$.data.temperature").value(0.55))
            .andExpect(jsonPath("$.data.maxTokens").value(1200));
    }

    @Test
    void shouldUpdateAndGetPromptConfigWithStructuredOutputFields() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"promptType":"deconstruct","promptName":"default-deconstruct","promptContent":"UPDATED {{content}}","modelName":"deepseek-chat","inputJsonSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"content\\":{\\"type\\":\\"string\\"}}}","inputExampleJson":"{\\"content\\":\\"example-input\\"}","outputJsonSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"summary\\":{\\"type\\":\\"string\\"}}}","outputExampleJson":"{\\"summary\\":\\"example\\"}","postProcessType":"json_extract","parseConfigJson":"{\\"parser\\":\\"json\\",\\"trimMarkdownFence\\":true}"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.inputJsonSchema").value("{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"}}}"))
            .andExpect(jsonPath("$.data.inputExampleJson").value("{\"content\":\"example-input\"}"))
            .andExpect(jsonPath("$.data.outputJsonSchema").value("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}}}"))
            .andExpect(jsonPath("$.data.outputExampleJson").value("{\"summary\":\"example\"}"))
            .andExpect(jsonPath("$.data.postProcessType").value("json_extract"))
            .andExpect(jsonPath("$.data.parseConfigJson").value("{\"parser\":\"json\",\"trimMarkdownFence\":true}"));

        mockMvc.perform(get("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .param("promptType", "deconstruct"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.inputJsonSchema").value("{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"}}}"))
            .andExpect(jsonPath("$.data.inputExampleJson").value("{\"content\":\"example-input\"}"))
            .andExpect(jsonPath("$.data.outputJsonSchema").value("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}}}"))
            .andExpect(jsonPath("$.data.outputExampleJson").value("{\"summary\":\"example\"}"))
            .andExpect(jsonPath("$.data.postProcessType").value("json_extract"))
            .andExpect(jsonPath("$.data.parseConfigJson").value("{\"parser\":\"json\",\"trimMarkdownFence\":true}"));
    }

    @Test
    void shouldBackfillDefaultContractsForLegacyDeconstructPromptConfig() throws Exception {
        jdbcTemplate.update("""
            UPDATE prompt_config
            SET input_json_schema = NULL,
                input_example_json = NULL,
                output_json_schema = NULL,
                output_example_json = NULL,
                post_process_type = NULL,
                parse_config_json = NULL
            WHERE prompt_type = 'deconstruct'
            """);
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .param("promptType", "deconstruct"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.inputJsonSchema").value(org.hamcrest.Matchers.containsString("\"chapters\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"sellingPoints\"")))
            .andExpect(jsonPath("$.data.outputExampleJson").value(org.hamcrest.Matchers.containsString("\"analysisType\": \"deconstruct\"")));

        String inputJsonSchema = jdbcTemplate.queryForObject(
            "SELECT input_json_schema FROM prompt_config WHERE prompt_type = 'deconstruct' AND deleted = 0 LIMIT 1",
            String.class
        );
        String outputJsonSchema = jdbcTemplate.queryForObject(
            "SELECT output_json_schema FROM prompt_config WHERE prompt_type = 'deconstruct' AND deleted = 0 LIMIT 1",
            String.class
        );
        assertThat(inputJsonSchema).contains("\"chapters\"");
        assertThat(outputJsonSchema).contains("\"sellingPoints\"");
    }

    @Test
    void shouldBackfillThemePromptContractWithBoardScopedFields() throws Exception {
        jdbcTemplate.update("""
            UPDATE prompt_config
            SET input_json_schema = NULL,
                input_example_json = NULL,
                output_json_schema = NULL,
                output_example_json = NULL,
                post_process_type = NULL,
                parse_config_json = NULL
            WHERE prompt_type = 'theme'
            """);
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .param("promptType", "theme"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.inputJsonSchema").value(org.hamcrest.Matchers.containsString("\"snapshotCount\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"boardSummary\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"historicalWordCloud\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"themeDistribution\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"representativeBooks\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"hotBooks\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"insightCards\"")))
            .andExpect(jsonPath("$.data.outputJsonSchema").value(org.hamcrest.Matchers.containsString("\"snapshotComparisons\"")))
            .andExpect(jsonPath("$.data.postProcessType").value("json_extract"))
            .andExpect(jsonPath("$.data.parseConfigJson").value(org.hamcrest.Matchers.containsString("\"parser\": \"json\"")));

        String parseConfigJson = jdbcTemplate.queryForObject(
            "SELECT parse_config_json FROM prompt_config WHERE prompt_type = 'theme' AND deleted = 0 LIMIT 1",
            String.class
        );
        assertThat(parseConfigJson).contains("\"parser\": \"json\"");
    }

    @Test
    void shouldForbidUserRoleUpdatingPromptConfig() throws Exception {
        String token = loginAndGetToken("writer", "writer123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promptType\":\"deconstruct\",\"promptName\":\"default-deconstruct\",\"promptContent\":\"USER-EDIT {{content}}\",\"modelName\":\"dify\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
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
    void shouldAnalyzeLegacySingleBookThroughWorkerAndPersistStructuredResult() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"promptType":"deconstruct","promptName":"default-deconstruct","promptContent":"JSON ONLY {{content}}","modelName":"deepseek-chat","temperature":0.55,"maxTokens":1200}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        MvcResult result = mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":3,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.modelName").value("langgraph-worker:deepseek-chat"))
            .andExpect(jsonPath("$.data.resultJson.source").value("mock-langgraph"))
            .andExpect(jsonPath("$.data.tokenUsed").value(156))
            .andReturn();

        String response = result.getResponse().getContentAsString();
        String content = JsonPath.read(response, "$.data.resultContent");
        assertThat(content).contains("langgraph deconstruct content");
        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("JSON ONLY {{content}}");

        String resultJson = jdbcTemplate.queryForObject(
            "SELECT result_json FROM analysis_result WHERE analysis_type='deconstruct' ORDER BY id DESC LIMIT 1",
            String.class
        );
        assertThat(resultJson).contains("\"source\":\"mock-langgraph\"");
    }

    @Test
    void shouldAnalyzeLegacySingleBookViaWorkerAndPreserveMetadata() throws Exception {
        updateSystemConfig("analysis.runtime.mode", "legacy");
        String token = loginAndGetToken("admin", "admin123");

        MvcResult result = mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisType").value("deconstruct"))
            .andExpect(jsonPath("$.data.modelName").value("langgraph-worker:deepseek-chat"))
            .andExpect(jsonPath("$.data.resultJson.source").value("mock-langgraph"))
            .andExpect(jsonPath("$.data.resultJson.analysisType").value("deconstruct"))
            .andExpect(jsonPath("$.data.resultJson.requestedChapterCount").value(2))
            .andExpect(jsonPath("$.data.resultJson.actualChapterCount").value(2))
            .andExpect(jsonPath("$.data.resultJson.inputChapterCount").value(2))
            .andExpect(jsonPath("$.data.resultJson.promptRuntime.promptType").value("deconstruct"))
            .andReturn();

        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("\"agentType\":\"deconstruct\"");
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("\"kind\":\"book_analysis\"");

        String response = result.getResponse().getContentAsString();
        String resultContent = JsonPath.read(response, "$.data.resultContent");
        assertThat(resultContent).contains("langgraph deconstruct content");

        String resultJson = jdbcTemplate.queryForObject(
            "SELECT result_json FROM analysis_result WHERE analysis_type='deconstruct' ORDER BY id DESC LIMIT 1",
            String.class
        );
        assertThat(resultJson).contains("\"source\":\"mock-langgraph\"");
        assertThat(resultJson).contains("\"analysisType\":\"deconstruct\"");
        assertThat(resultJson).contains("\"requestedChapterCount\":2");
        assertThat(resultJson).contains("\"actualChapterCount\":2");
        assertThat(resultJson).contains("\"inputChapterCount\":2");
        assertThat(resultJson).contains("\"promptRuntime\"");
    }

    @Test
    void shouldAnalyzeChunkedLegacySingleBookViaWorkerAndPreserveMetadata() throws Exception {
        updateSystemConfig("analysis.runtime.mode", "legacy");
        updateSystemConfig("analysis.chunk.max-input-tokens", "1000");
        updateSystemConfig("analysis.chunk.target-input-tokens", "1000");
        long bookId = insertBook("fanqie", "legacy-chunk-worker-1", "Legacy Chunk Worker Book", "Author Chunk",
            "Chunk intro", "https://fanqienovel.com/page/legacy-chunk-worker-1");
        insertChapter(bookId, 1, "Chapter 1", "A".repeat(5000));
        insertChapter(bookId, 2, "Chapter 2", "B".repeat(5000));
        insertChapter(bookId, 3, "Chapter 3", "C".repeat(5000));

        MvcResult result = mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + loginAndGetToken("admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":%d,"chapterCount":3,"forceReanalyze":true}
                    """.formatted(bookId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisType").value("deconstruct"))
            .andExpect(jsonPath("$.data.modelName").value("langgraph-worker:deepseek-chat"))
            .andExpect(jsonPath("$.data.resultJson.source").value("mock-langgraph"))
            .andExpect(jsonPath("$.data.resultJson.analysisType").value("deconstruct"))
            .andExpect(jsonPath("$.data.resultJson.requestedChapterCount").value(3))
            .andExpect(jsonPath("$.data.resultJson.actualChapterCount").value(3))
            .andExpect(jsonPath("$.data.resultJson.inputChapterCount").value(3))
            .andExpect(jsonPath("$.data.resultJson.promptRuntime.promptType").value("deconstruct"))
            .andReturn();

        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("\"agentType\":\"deconstruct\"");
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("\"kind\":\"book_analysis\"");

        String response = result.getResponse().getContentAsString();
        String resultContent = JsonPath.read(response, "$.data.resultContent");
        assertThat(resultContent).contains("langgraph deconstruct content");

        String resultJson = jdbcTemplate.queryForObject(
            "SELECT result_json FROM analysis_result WHERE analysis_type='deconstruct' ORDER BY id DESC LIMIT 1",
            String.class
        );
        assertThat(resultJson).contains("\"source\":\"mock-langgraph\"");
        assertThat(resultJson).contains("\"analysisType\":\"deconstruct\"");
        assertThat(resultJson).contains("\"requestedChapterCount\":3");
        assertThat(resultJson).contains("\"actualChapterCount\":3");
        assertThat(resultJson).contains("\"inputChapterCount\":3");
        assertThat(resultJson).contains("\"promptRuntime\"");
    }

    @Test
    void shouldAnalyzeViaLangGraphRuntimeWhenSystemModeIsEnabled() throws Exception {
        updateSystemConfig("analysis.runtime.mode", "langgraph");
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.modelName").value("langgraph-worker:deepseek-chat"))
            .andExpect(jsonPath("$.data.resultJson.source").value("mock-langgraph"))
            .andExpect(jsonPath("$.data.resultJson.analysisType").value("deconstruct"));

        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("\"agentType\":\"deconstruct\"");
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("\"platform\":\"fanqie\"");
    }

    @Test
    void shouldUseSelectedModelRegistryRuntimeConfigForOpenAiCompatibleAnalysis() throws Exception {
        String adminToken = loginAndGetToken("admin", "admin123");
        String writerToken = loginAndGetToken("writer", "writer123");

        mockMvc.perform(put("/api/config/system/model-registry")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "defaultModelKey":"deepseek-chat",
                      "models":[
                        {
                          "modelKey":"deepseek-chat",
                          "displayName":"DeepSeek Chat",
                          "providerType":"openai-compatible",
                          "modelName":"deepseek-chat",
                          "baseUrl":"http://127.0.0.1:%d/v1",
                          "apiKey":"registry-key-1",
                          "enabled":true,
                          "isDefault":true,
                          "defaultTemperature":1.0,
                          "maxTokens":4096,
                          "temperatureSpecJson":"{\\"min\\":0.0,\\"max\\":2.0,\\"step\\":0.1,\\"default\\":1.0}"
                        },
                        {
                          "modelKey":"deepseek-reasoner",
                          "displayName":"DeepSeek Reasoner",
                          "providerType":"openai-compatible",
                          "modelName":"deepseek-reasoner",
                          "baseUrl":"http://127.0.0.1:%d/v1",
                          "apiKey":"registry-key-2",
                          "enabled":true,
                          "isDefault":false,
                          "defaultTemperature":0.7,
                          "maxTokens":8192,
                          "temperatureSpecJson":"{\\"min\\":0.0,\\"max\\":1.5,\\"step\\":0.1,\\"default\\":0.7}"
                        }
                      ]
                    }
                    """.formatted(
                    MOCK_OPENAI_SERVER.getAddress().getPort(),
                    MOCK_OPENAI_SERVER.getAddress().getPort()
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put("/api/config/user")
                .header("Authorization", "Bearer " + writerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"configKey":"ai.preferred-model","configValue":"deepseek-reasoner"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + writerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":3,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.modelName").value("langgraph-worker:deepseek-reasoner"));

        String normalizedRequestBody = LAST_LANGGRAPH_REQUEST_BODY.get().replaceAll("\\s+", "");
        assertThat(normalizedRequestBody).contains("\"modelName\":\"deepseek-reasoner\"");
        assertThat(normalizedRequestBody).contains("\"modelKey\":\"deepseek-reasoner\"");
        assertThat(normalizedRequestBody).contains("\"temperature\":0.7");
        assertThat(normalizedRequestBody).contains("\"apiKey\":\"registry-key-2\"");
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
    }

    @Test
    void shouldStreamDeconstructViaLangGraphRuntimeWhenSystemModeIsEnabled() throws Exception {
        updateSystemConfig("analysis.runtime.mode", "langgraph");
        String token = loginAndGetToken("admin", "admin123");

        MvcResult streamStart = mockMvc.perform(post("/api/analysis/deconstruct/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        streamStart.getAsyncResult(10000);
        String body = streamStart.getResponse().getContentAsString();

        assertThat(body).contains("\"event\":\"start\"");
        assertThat(body).contains("mock langgraph stream");
        assertThat(body).contains("\"event\":\"done\"");
        assertThat(body).contains("\"source\":\"mock-langgraph\"");
        assertThat(body).contains("\"runtimeMode\":\"langgraph\"");
        assertThat(body).contains("\"providerLatencyMillis\":321");
        assertThat(body).doesNotContain("\"event\":\"metrics\"");
        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldReplayCachedDeconstructStreamWithoutCallingLangGraphWorker() throws Exception {
        updateSystemConfig("analysis.runtime.mode", "langgraph");
        clearCrawlerLocalCache();
        LANGGRAPH_REQUEST_COUNT.set(0);
        LAST_LANGGRAPH_REQUEST_BODY.set("");
        jdbcTemplate.update("""
            INSERT INTO analysis_result (
                user_id, platform, book_id, analysis_type, chapter_count, prompt_config_id,
                model_name, result_content, result_json, token_used, cost_time, create_time, update_time, deleted
            ) VALUES (
                1, 'fanqie', 1001, 'deconstruct', 3, 2001,
                'langgraph-worker:deepseek-chat',
                'cached deconstruct content',
                '{"analysisType":"deconstruct","summary":"cached summary","detailContent":"cached deconstruct content","source":"cached-history"}',
                101, 12, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP(), 0
            )
            """);
        String token = loginAndGetToken("admin", "admin123");

        MvcResult streamStart = mockMvc.perform(post("/api/analysis/deconstruct/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":3}
                    """))
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        streamStart.getAsyncResult(10000);
        String body = streamStart.getResponse().getContentAsString();

        assertThat(body).contains("\"event\":\"start\"");
        assertThat(body).contains("cached deconstruct content");
        assertThat(body).contains("\"event\":\"done\"");
        assertThat(body).contains("\"source\":\"cached-history\"");
        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isZero();
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).isBlank();
    }

    @Test
    void shouldAnalyzeTrendViaLangGraphRuntimeWhenSystemModeIsEnabled() throws Exception {
        insertThemePromptConfig();
        updateSystemConfig("analysis.runtime.mode", "langgraph");
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/analysis/trend")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisType").value("theme"))
            .andExpect(jsonPath("$.data.resultJson.source").value("mock-langgraph"))
            .andExpect(jsonPath("$.data.resultJson.analysisType").value("theme"));

        Map<String, Object> requestPayload = JsonPath.parse(LAST_LANGGRAPH_REQUEST_BODY.get()).read("$");
        Map<String, Object> promptConfig = JsonPath.read(LAST_LANGGRAPH_REQUEST_BODY.get(), "$.promptConfig");
        Map<String, Object> expectedPromptConfig = jdbcTemplate.queryForMap("""
            SELECT input_json_schema,
                   input_example_json,
                   output_json_schema,
                   output_example_json,
                   parse_config_json,
                   post_process_type
            FROM prompt_config
            WHERE prompt_type = 'theme' AND deleted = 0
            LIMIT 1
            """);

        assertThat(requestPayload.get("agentType")).isEqualTo("trend_theme");
        assertThat(promptConfig.get("inputJsonSchema")).isEqualTo(expectedPromptConfig.get("input_json_schema"));
        assertThat(promptConfig.get("inputExampleJson")).isEqualTo(expectedPromptConfig.get("input_example_json"));
        assertThat(promptConfig.get("outputJsonSchema")).isEqualTo(expectedPromptConfig.get("output_json_schema"));
        assertThat(promptConfig.get("outputExampleJson")).isEqualTo(expectedPromptConfig.get("output_example_json"));
        assertThat(promptConfig.get("parseConfigJson")).isEqualTo(expectedPromptConfig.get("parse_config_json"));
        assertThat(promptConfig.get("postProcessType")).isEqualTo(expectedPromptConfig.get("post_process_type"));
    }

    @Test
    void shouldSkipBrokenPersistedTrendResultAndReinvokeLangGraph() throws Exception {
        insertThemePromptConfig();
        updateSystemConfig("analysis.runtime.mode", "langgraph");
        clearCrawlerLocalCache();
        jdbcTemplate.update("""
            UPDATE analysis_result
            SET result_content = ?,
                result_json = ?,
                create_time = CURRENT_TIMESTAMP,
                update_time = CURRENT_TIMESTAMP
            WHERE id = 3004
            """,
            "{\"summary\":\"broken",
            """
                {
                  "analysisType":"theme",
                  "summary":"",
                  "boardSummary":"",
                  "trendPreview":"{\\"summary\\":\\"broken",
                  "detailContent":"{\\"summary\\":\\"broken",
                  "historicalWordCloud":[],
                  "themeDistribution":[],
                  "themeTable":[],
                  "hotBooks":[],
                  "insightCards":[],
                  "snapshotComparisons":[],
                  "comparisonSummary":"",
                  "historyAnalysisCount":3
                }
                """
        );
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/analysis/trend")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.resultJson.source").value("mock-langgraph"))
            .andExpect(jsonPath("$.data.resultJson.summary").value(org.hamcrest.Matchers.containsString("langgraph theme summary")));

        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(LAST_LANGGRAPH_REQUEST_BODY.get()).contains("\"agentType\":\"trend_theme\"");
    }

    @Test
    void shouldStreamTrendViaLangGraphRuntimeWhenSystemModeIsEnabled() throws Exception {
        insertThemePromptConfig();
        updateSystemConfig("analysis.runtime.mode", "langgraph");
        String token = loginAndGetToken("admin", "admin123");

        MvcResult streamStart = mockMvc.perform(post("/api/analysis/trend/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain"}
                    """))
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        streamStart.getAsyncResult(10000);
        String body = streamStart.getResponse().getContentAsString();

        assertThat(body).contains("\"event\":\"start\"");
        assertThat(body).contains("\"analysisType\":\"theme\"");
        assertThat(body).contains("\"source\":\"mock-langgraph\"");
        assertThat(body).contains("\"event\":\"done\"");
    }

    @Test
    void shouldSendPromptPrefixAsSystemMessageForOpenAiCompatibleModel() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"promptType":"deconstruct","promptName":"default-deconstruct","promptContent":"SYSTEM PREFIX\\n{{content}}\\nJSON ONLY","modelName":"deepseek-chat","temperature":0.55,"maxTokens":1200}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        String requestBody = LAST_LANGGRAPH_REQUEST_BODY.get();
        assertThat(requestBody).contains("\"promptContent\":\"SYSTEM PREFIX");
        assertThat(requestBody).contains("SYSTEM PREFIX");
        assertThat(requestBody).contains("JSON ONLY");
        assertThat(requestBody).contains("Book:");
        assertThat(requestBody).contains("Author:");
        assertThat(requestBody).contains("[");
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
    }

    @Test
    void shouldAttachJsonOutputHintsWhenPromptConfigRequestsStructuredOutput() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(put("/api/config/prompt")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"promptType":"deconstruct","promptName":"default-deconstruct","promptContent":"SYSTEM PREFIX\\n{{content}}","modelName":"deepseek-chat","outputJsonSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"summary\\":{\\"type\\":\\"string\\"},\\"analysisType\\":{\\"type\\":\\"string\\"}}}","outputExampleJson":"{\\"summary\\":\\"example\\",\\"analysisType\\":\\"deconstruct\\"}","postProcessType":"json_extract","parseConfigJson":"{\\"parser\\":\\"json\\",\\"trimMarkdownFence\\":true}"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        String requestBody = LAST_LANGGRAPH_REQUEST_BODY.get();
        assertThat(requestBody).contains("\"outputJsonSchema\":");
        assertThat(requestBody).contains("\\\"summary\\\"");
        assertThat(requestBody).contains("\"outputExampleJson\":");
        assertThat(requestBody).contains("\"postProcessType\":\"json_extract\"");
        assertThat(requestBody).contains("\"parseConfigJson\":");
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
    }

    @Test
    void shouldAttachJsonOutputHintsForStructureAnalysis() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(post("/api/analysis/structure")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        String requestBody = LAST_LANGGRAPH_REQUEST_BODY.get();
        assertThat(requestBody).contains("\"agentType\":\"structure\"");
        assertThat(requestBody).contains("\"outputJsonSchema\":");
        assertThat(requestBody).contains("\"postProcessType\":\"json_extract\"");
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
    }

    @Test
    void shouldAttachJsonOutputHintsForPlotAnalysis() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(post("/api/analysis/plot")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        String requestBody = LAST_LANGGRAPH_REQUEST_BODY.get();
        assertThat(requestBody).contains("\"agentType\":\"plot\"");
        assertThat(requestBody).contains("\"outputJsonSchema\":");
        assertThat(requestBody).contains("\"postProcessType\":\"json_extract\"");
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
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
    void shouldReusePersistedAnalysisResultWithinCacheWindowWhenLocalCacheIsCleared() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        updatePromptConfig(token, "JSON ONLY {{content}}");

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
        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();

        clearCrawlerLocalCache();

        MvcResult secondResult = mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        Number secondId = JsonPath.read(secondResult.getResponse().getContentAsString(), "$.data.id");
        Integer resultCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM analysis_result WHERE book_id = 1001 AND analysis_type = 'deconstruct' AND chapter_count = 2",
            Integer.class
        );

        assertThat(secondId.longValue()).isEqualTo(firstId.longValue());
        assertThat(resultCount).isEqualTo(1);
        assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
    }

    @Test
    void shouldFallbackToBuiltInPromptAndCarrySelectedChapterContentWhenPromptConfigIsBroken() throws Exception {
        long bookId = insertBook("fanqie", "analysis-broken-1", "Broken Prompt Book", "Author A",
            "Intro A", "https://fanqienovel.com/page/analysis-broken-1");
        insertChapter(bookId, 1, "Chapter 1", "ALPHA chapter one");
        insertChapter(bookId, 2, "Chapter 2", "BETA chapter two");
        insertChapter(bookId, 3, "Chapter 3", "GAMMA chapter three");
        updatePromptConfigDirectly(1L, "联调更新提示词", "deepseek-chat");

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":%d,"chapterCount":2,"forceReanalyze":true}
                    """.formatted(bookId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.modelName").value("langgraph-worker:deepseek-chat"));

        String requestBody = LAST_LANGGRAPH_REQUEST_BODY.get();
        assertThat(requestBody).contains("ALPHA chapter one");
        assertThat(requestBody).contains("BETA chapter two");
        assertThat(requestBody).doesNotContain("GAMMA chapter three");
        assertThat(OPENAI_REQUEST_COUNT.get()).isZero();
        assertThat(requestBody).doesNotContain("联调更新提示词");
    }

    @Test
    void shouldStreamDeconstructAnalysisWithSseProtocol() throws Exception {
        updateSystemConfig("ai.openai-compatible.streaming-enabled", "false");
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
    void shouldRespectSystemConfigWhenCheckingStreamingSwitch() {
        updateSystemConfig("ai.openai-compatible.streaming-enabled", "true");
        Boolean enabled = ReflectionTestUtils.invokeMethod(aiGatewayService, "isOpenAiCompatibleStreamingEnabled");
        assertThat(enabled).isTrue();
    }

    @Test
    void shouldUseRealStreamingWhenSystemSwitchIsEnabled() throws Exception {
        updateSystemConfig("ai.openai-compatible.streaming-enabled", "true");
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

        assertThat(body).contains("\"event\":\"start\"");
        assertThat(body).doesNotContain("mock summary DEFAULT");
        assertThat(LAST_OPENAI_REQUEST_BODY.get()).contains("\"stream\" : true");
    }

    @Test
    void shouldEmitProgressEventBeforeSlowStreamingProviderReturnsFirstDelta() throws Exception {
        updateSystemConfig("ai.openai-compatible.streaming-enabled", "true");
        updatePromptConfigDirectly(1L, "SLOW_STREAM {{content}}", "deepseek-chat");
        String token = loginAndGetToken("admin", "admin123");

        MvcResult streamStart = mockMvc.perform(post("/api/analysis/deconstruct/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":1001,"chapterCount":2,"forceReanalyze":true}
                    """))
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        streamStart.getAsyncResult(10000);
        String body = streamStart.getResponse().getContentAsString();

        assertThat(body).contains("[analysis-progress]");
        assertThat(body).contains("mock summary STREAM");
    }

    @Test
    void shouldTreatAppStreamingFlagAsGlobalKillSwitch() throws Exception {
        updateSystemConfig("ai.openai-compatible.streaming-enabled", "true");
        boolean original = aiProperties.getOpenAiCompatible().isStreamingEnabled();
        aiProperties.getOpenAiCompatible().setStreamingEnabled(false);
        try {
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

            assertThat(body).contains("\"event\":\"done\"");
            assertThat(body).contains("langgraph deconstruct content");
            assertThat(body).doesNotContain("mock summary STREAM");
            assertThat(LAST_OPENAI_REQUEST_BODY.get()).doesNotContain("\"stream\":true");
            assertThat(LANGGRAPH_REQUEST_COUNT.get()).isEqualTo(1);
        } finally {
            aiProperties.getOpenAiCompatible().setStreamingEnabled(original);
        }
    }

    @Test
    void shouldEmitChunkProgressDeltaWhenChunkedAnalysisTakesOver() throws Exception {
        updateSystemConfig("analysis.chunk.max-input-tokens", "1000");
        updateSystemConfig("analysis.chunk.target-input-tokens", "1000");
        long bookId = insertBook("fanqie", "stream-chunk-1", "Chunk Stream Book", "Author Chunk",
            "Chunk intro", "https://fanqienovel.com/page/stream-chunk-1");
        insertChapter(bookId, 1, "Chapter 1", "A".repeat(5000));
        insertChapter(bookId, 2, "Chapter 2", "B".repeat(5000));
        insertChapter(bookId, 3, "Chapter 3", "C".repeat(5000));

        String token = loginAndGetToken("admin", "admin123");
        MvcResult streamStart = mockMvc.perform(post("/api/analysis/deconstruct/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":%d,"chapterCount":3,"forceReanalyze":true}
                    """.formatted(bookId)))
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        streamStart.getAsyncResult(10000);
        String body = streamStart.getResponse().getContentAsString();

        assertThat(body).contains("[chunk-progress]");
        assertThat(body).contains("1/3");
    }

    @Test
    void shouldAvoidChunkMergeForDeepSeekWhenLegacyThresholdWouldBeTooConservative() throws Exception {
        long bookId = insertBook("fanqie", "deepseek-long-1", "DeepSeek Long Book", "Author Long",
            "Long intro", "https://fanqienovel.com/page/deepseek-long-1");
        insertChapter(bookId, 1, "Chapter 1", "A".repeat(20000));
        insertChapter(bookId, 2, "Chapter 2", "B".repeat(20000));
        insertChapter(bookId, 3, "Chapter 3", "C".repeat(20000));
        insertChapter(bookId, 4, "Chapter 4", "D".repeat(20000));
        insertChapter(bookId, 5, "Chapter 5", "E".repeat(20000));
        insertChapter(bookId, 6, "Chapter 6", "F".repeat(20000));
        insertChapter(bookId, 7, "Chapter 7", "G".repeat(20000));
        insertChapter(bookId, 8, "Chapter 8", "H".repeat(20000));
        insertChapter(bookId, 9, "Chapter 9", "I".repeat(20000));
        insertChapter(bookId, 10, "Chapter 10", "J".repeat(20000));

        String token = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/analysis/deconstruct")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","bookId":%d,"chapterCount":10,"forceReanalyze":true}
                    """.formatted(bookId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.resultJson.analysisMode").doesNotExist());
    }

    @Test
    void shouldStreamTrendAnalysisWithSseProtocol() throws Exception {
        insertThemePromptConfig();
        String token = loginAndGetToken("admin", "admin123");

        MvcResult streamStart = mockMvc.perform(post("/api/analysis/trend/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"platform":"fanqie","channelCode":"male-new","boardCode":"urban-brain"}
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
        String phone = switch (username) {
            case "admin" -> "13800138000";
            case "writer" -> "13800138001";
            default -> username;
        };
        MvcResult result = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}"))
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

    private static HttpServer startMockOpenAiServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_OPENAI_REQUEST_BODY.set(requestBody);
                LAST_OPENAI_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                OPENAI_REQUEST_COUNT.incrementAndGet();
                if (requestBody.contains("\"stream\":true") || requestBody.contains("\"stream\" : true")) {
                    if (requestBody.contains("SLOW_STREAM")) {
                        try {
                            Thread.sleep(2500L);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] response = """
                        data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","created":1760000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"{\\"summary\\":\\"mock summary STREAM\\","},"finish_reason":null}]}

                        data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","created":1760000001,"model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"\\"source\\":\\"mock-openai\\",\\"analysisType\\":\\"deconstruct\\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":80,"total_tokens":200}}

                        data: [DONE]

                        """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream outputStream = exchange.getResponseBody()) {
                        outputStream.write(response);
                    }
                    return;
                }
                String marker = extractPromptMarker(requestBody);
                byte[] response = """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 1760000000,
                      "model": "deepseek-chat",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "{\\"summary\\":\\"mock summary %s\\",\\"source\\":\\"mock-openai\\",\\"analysisType\\":\\"deconstruct\\"}"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 120,
                        "completion_tokens": 80,
                        "total_tokens": 200
                      }
                    }
                    """.formatted(marker).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(response);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start mock OpenAI server", ex);
        }
    }

    private static HttpServer startMockLangGraphServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/analysis/run", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_LANGGRAPH_REQUEST_BODY.set(requestBody);
                LANGGRAPH_REQUEST_COUNT.incrementAndGet();
                String analysisType = extractLangGraphAnalysisType(requestBody);
                String modelName = "langgraph-worker:" + extractJsonString(requestBody, "modelName", "deepseek-chat");
                String marker = extractPromptMarker(requestBody);
                byte[] response = """
                    {
                      "taskId": "langgraph-task-1",
                      "modelName": "%s",
                      "content": "langgraph %s content %s",
                      "tokenUsed": 156,
                      "resultJson": {
                        "analysisType": "%s",
                        "summary": "langgraph %s summary %s",
                        "detailContent": "langgraph %s content %s",
                        "source": "mock-langgraph",
                        "historicalWordCloud": [],
                        "themeTable": [],
                        "hotBooks": [],
                        "insightCards": [],
                        "snapshotComparisons": []
                      }
                    }
                    """.formatted(
                    modelName,
                    analysisType,
                    marker,
                    analysisType,
                    analysisType,
                    marker,
                    analysisType,
                    marker
                ).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(response);
                }
            });
            server.createContext("/internal/analysis/run/stream", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_LANGGRAPH_REQUEST_BODY.set(requestBody);
                LANGGRAPH_REQUEST_COUNT.incrementAndGet();
                String analysisType = requestBody.contains("\"agentType\":\"trend_theme\"") ? "theme" : "deconstruct";
                byte[] response = """
                    event: start
                    data: {"event":"start","analysisType":"%s","taskId":"langgraph-task-1"}

                    event: delta
                    data: {"event":"delta","delta":"mock langgraph stream "}

                    event: delta
                    data: {"event":"delta","delta":"payload"}

                    event: metrics
                    data: {"event":"metrics","metrics":{"runtimeMode":"langgraph","agentType":"%s","providerLatencyMillis":321,"queueWaitMillis":18,"totalDurationMillis":654}}

                    event: done
                    data: {"event":"done","data":{"taskId":"langgraph-task-1","modelName":"langgraph-worker:deepseek-chat","content":"mock langgraph stream payload","resultJson":{"analysisType":"%s","summary":"langgraph stream summary","detailContent":"mock langgraph stream payload","source":"mock-langgraph","historicalWordCloud":[],"themeTable":[],"hotBooks":[],"insightCards":[],"snapshotComparisons":[]},"tokenUsed":189}}

                    """.formatted(analysisType, analysisType, analysisType).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(response);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start mock LangGraph server", ex);
        }
    }

    private static String extractPromptMarker(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return "";
        }
        if (requestBody.contains("PROMPT-V1")) {
            return "PROMPT-V1";
        }
        if (requestBody.contains("PROMPT-V2")) {
            return "PROMPT-V2";
        }
        if (requestBody.contains("JSON ONLY")) {
            return "JSON ONLY";
        }
        return "DEFAULT";
    }

    private static String extractLangGraphAnalysisType(String requestBody) {
        if (requestBody.contains("\"agentType\":\"trend_theme\"")) {
            return "theme";
        }
        if (requestBody.contains("\"agentType\":\"structure\"")) {
            return "structure";
        }
        if (requestBody.contains("\"agentType\":\"plot\"")) {
            return "plot";
        }
        return "deconstruct";
    }

    private static String extractJsonString(String requestBody, String fieldName, String fallback) {
        if (requestBody == null || requestBody.isBlank()) {
            return fallback;
        }
        String marker = "\"" + fieldName + "\":\"";
        int start = requestBody.indexOf(marker);
        if (start < 0) {
            return fallback;
        }
        int valueStart = start + marker.length();
        int valueEnd = requestBody.indexOf('"', valueStart);
        if (valueEnd <= valueStart) {
            return fallback;
        }
        return requestBody.substring(valueStart, valueEnd);
    }

    @SuppressWarnings("unchecked")
    private void clearCrawlerLocalCache() {
        Map<String, Object> localCache = (Map<String, Object>) ReflectionTestUtils.getField(crawlerCacheService, "localCache");
        if (localCache != null) {
            localCache.clear();
        }
    }

    private long insertBook(String platform,
                            String platformBookId,
                            String bookName,
                            String author,
                            String intro,
                            String bookUrl) {
        jdbcTemplate.update(
            """
                INSERT INTO crawl_book(platform, platform_book_id, book_name, author, intro, book_url, deleted)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """,
            platform,
            platformBookId,
            bookName,
            author,
            intro,
            bookUrl
        );
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM crawl_book WHERE platform = ? AND platform_book_id = ? AND deleted = 0 LIMIT 1",
            Long.class,
            platform,
            platformBookId
        );
        assertThat(id).isNotNull();
        return id;
    }

    private void insertChapter(long bookId, int chapterNo, String chapterTitle, String content) {
        jdbcTemplate.update(
            """
                INSERT INTO crawl_chapter(platform, book_id, chapter_no, chapter_title, content, word_count, source_word_count, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """,
            "fanqie",
            bookId,
            chapterNo,
            chapterTitle,
            content,
            content.length(),
            content.length()
        );
    }

    private void updatePromptConfigDirectly(Long id, String promptContent, String modelName) {
        jdbcTemplate.update(
            """
                UPDATE prompt_config
                SET prompt_content = ?, model_name = ?, update_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
            promptContent,
            modelName,
            id
        );
    }

    private void updateSystemConfig(String configKey, String configValue) {
        int updated = jdbcTemplate.update(
            """
                UPDATE system_config
                SET config_value = ?, update_time = CURRENT_TIMESTAMP
                WHERE config_key = ?
                """,
            configValue,
            configKey
        );
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update(
            """
                INSERT INTO system_config(config_key, config_value, config_type, description, is_editable, deleted)
                VALUES (?, ?, 'analysis', ?, 1, 0)
                """,
            configKey,
            configValue,
            configKey
        );
    }
}

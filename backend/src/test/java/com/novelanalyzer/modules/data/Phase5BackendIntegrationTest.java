package com.novelanalyzer.modules.data;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:phase5db;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.database=14",
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
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql",
        "classpath:sql/phase4-data-h2.sql",
        "classpath:sql/phase5-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class Phase5BackendIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clearRedisCache() {
        RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldManageSystemConfig() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/config/system")
                .header("Authorization", "Bearer " + token)
                .param("configKey", "ai.timeout.millis"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.configKey").value("ai.timeout.millis"))
            .andExpect(jsonPath("$.data.configValue").value("15000"));

        mockMvc.perform(put("/api/config/system")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"configKey":"ai.timeout.millis","configValue":"20000","description":"Updated timeout"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.configValue").value("20000"));
    }

    @Test
    void shouldManageAiModelRegistryAndExposeModelOptions() throws Exception {
        String adminToken = loginAndGetToken("admin", "admin123");
        String writerToken = loginAndGetToken("writer", "writer123");

        mockMvc.perform(get("/api/config/system/model-registry")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.defaultModelKey").isNotEmpty())
            .andExpect(jsonPath("$.data.models.length()").value(1))
            .andExpect(jsonPath("$.data.models[0].modelKey").value("deepseek-chat"));

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
                          "baseUrl":"https://api.deepseek.com/v1",
                          "apiKey":"registry-key-1",
                          "enabled":true,
                          "isDefault":true,
                          "defaultTemperature":1.0,
                          "maxTokens":8192,
                          "temperatureSpecJson":"{\\"min\\":0.0,\\"max\\":2.0,\\"step\\":0.1,\\"default\\":1.0}"
                        },
                        {
                          "modelKey":"deepseek-reasoner",
                          "displayName":"DeepSeek Reasoner",
                          "providerType":"openai-compatible",
                          "modelName":"deepseek-reasoner",
                          "baseUrl":"https://api.deepseek.com/v1",
                          "apiKey":"registry-key-2",
                          "enabled":true,
                          "isDefault":false,
                          "defaultTemperature":0.7,
                          "maxTokens":16384,
                          "temperatureSpecJson":"{\\"min\\":0.0,\\"max\\":1.5,\\"step\\":0.1,\\"default\\":0.7}"
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.models.length()").value(2))
            .andExpect(jsonPath("$.data.models[1].modelKey").value("deepseek-reasoner"))
            .andExpect(jsonPath("$.data.models[1].temperatureSpecJson").value("{\"min\":0.0,\"max\":1.5,\"step\":0.1,\"default\":0.7}"));

        mockMvc.perform(get("/api/config/system/model-options")
                .header("Authorization", "Bearer " + writerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].modelKey").value("deepseek-chat"))
            .andExpect(jsonPath("$.data[0].displayName").value("DeepSeek Chat"))
            .andExpect(jsonPath("$.data[1].modelKey").value("deepseek-reasoner"));

        mockMvc.perform(get("/api/config/system/available-models")
                .header("Authorization", "Bearer " + writerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0]").value("deepseek-chat"))
            .andExpect(jsonPath("$.data[1]").value("deepseek-reasoner"));
    }

    @Test
    void shouldReturn400WhenSystemConfigKeyMissing() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/config/system")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void shouldBootstrapMissingCrawlerRuntimeSystemConfigDefaults() throws Exception {
        jdbcTemplate.update("DELETE FROM system_config WHERE config_key IN (?, ?)",
            "crawler.http.timeout-seconds",
            "crawler.chapter.fetch-workers");
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/config/system")
                .header("Authorization", "Bearer " + token)
                .param("configKey", "crawler.http.timeout-seconds"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.configKey").value("crawler.http.timeout-seconds"))
            .andExpect(jsonPath("$.data.configValue").value("20"));

        mockMvc.perform(get("/api/config/system")
                .header("Authorization", "Bearer " + token)
                .param("configKey", "crawler.chapter.fetch-workers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.configKey").value("crawler.chapter.fetch-workers"))
            .andExpect(jsonPath("$.data.configValue").value("3"));
    }

    @Test
    void shouldReturn400WhenTrendPlatformBlank() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/analysis/trend")
                .header("Authorization", "Bearer " + token)
                .param("platform", " ")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void shouldForbidUserManagingSystemConfig() throws Exception {
        String token = loginAndGetToken("writer", "writer123");

        mockMvc.perform(get("/api/config/system")
                .header("Authorization", "Bearer " + token)
                .param("configKey", "ai.timeout.millis"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldReturnHistoryAndBoardScopedVisualData() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/data/history")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(4))
            .andExpect(jsonPath("$.data[0].analysisType").isNotEmpty())
            .andExpect(jsonPath("$.data[0].resultJson.analysisType").exists());

        mockMvc.perform(get("/api/data/visual")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.platform").value("fanqie"))
            .andExpect(jsonPath("$.data.channelCode").value("male-new"))
            .andExpect(jsonPath("$.data.boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.data.boardName").isNotEmpty())
            .andExpect(jsonPath("$.data.sourceSnapshotCount").value(3))
            .andExpect(jsonPath("$.data.historyAnalysisCount").value(3))
            .andExpect(jsonPath("$.data.latestSnapshots.length()").value(3))
            .andExpect(jsonPath("$.data.boardSummary").isNotEmpty())
            .andExpect(jsonPath("$.data.historicalWordCloud.length()").value(2))
            .andExpect(jsonPath("$.data.themeDistribution.length()").value(2))
            .andExpect(jsonPath("$.data.themeTable.length()").value(2))
            .andExpect(jsonPath("$.data.themeTable[0].ratio").value(50.0))
            .andExpect(jsonPath("$.data.themeTable[0].representativeBooks.length()").value(1))
            .andExpect(jsonPath("$.data.hotBooks.length()").value(1))
            .andExpect(jsonPath("$.data.hotBooks[0].rankNo").value(1))
            .andExpect(jsonPath("$.data.insightCards.length()").value(2))
            .andExpect(jsonPath("$.data.comparisonSummary").isNotEmpty())
            .andExpect(jsonPath("$.data.snapshotComparisons.length()").value(3))
            .andExpect(jsonPath("$.data.snapshotComparisons[0].leadBookName").isNotEmpty());
    }

    @Test
    void shouldRecoverStructuredTrendFieldsFromRawJsonStoredInDetailContent() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        String nestedThemeJson = """
            {
              "analysisType":"theme",
              "summary":"Urban-brain remains the clearest direction across the latest board snapshots.",
              "boardSummary":"This board keeps concentrating on urban-brain and system-flow hybrids, with the top title staying highly stable.",
              "trendPreview":"Urban-brain continues to dominate this board.",
              "detailContent":"Detailed board trend analysis for the last three snapshots.",
              "historicalWordCloud":[
                {"name":"urban-brain","value":24},
                {"name":"system-flow","value":15}
              ],
              "themeDistribution":[
                {"theme":"urban-brain","count":3,"ratio":50.0},
                {"theme":"system-flow","count":2,"ratio":33.3}
              ],
              "themeTable":[
                {
                  "theme":"urban-brain",
                  "count":3,
                  "ratio":50.0,
                  "trend":"rising",
                  "representativeBooks":[
                    {"theme":"urban-brain","bookName":"Brain City King","author":"Author One","rankNo":1,"reason":"Keeps leading the board"}
                  ]
                }
              ],
              "hotBooks":[
                {"theme":"urban-brain","bookName":"Brain City King","author":"Author One","rankNo":1,"reason":"Keeps leading the board"}
              ],
              "insightCards":[
                {"label":"Lead lane","value":"urban-brain","note":"Dominates the board history"},
                {"label":"Lead title","value":"Brain City King","note":"Most representative latest book"}
              ],
              "snapshotComparisons":[
                {"snapshotTime":"2026-03-20 11:30:00","topTheme":"urban-brain","topThemeRatio":50.0,"leadBookName":"Brain City King","change":"holding"}
              ],
              "comparisonSummary":"Urban-brain has become the clearest board-level direction across the last three snapshots.",
              "historyAnalysisCount":3
            }
            """;
        String degradedStoredJson = """
            {
              "analysisType":"theme",
              "summary":"",
              "boardSummary":"",
              "trendPreview":%s,
              "detailContent":%s,
              "historicalWordCloud":[],
              "themeDistribution":[],
              "themeTable":[],
              "hotBooks":[],
              "insightCards":[],
              "snapshotComparisons":[],
              "comparisonSummary":"",
              "historyAnalysisCount":3
            }
            """.formatted(jsonStringLiteral(nestedThemeJson), jsonStringLiteral(nestedThemeJson));

        jdbcTemplate.update(
            "UPDATE analysis_result SET result_json = ?, result_content = ? WHERE id = ?",
            degradedStoredJson,
            nestedThemeJson,
            3004L
        );

        mockMvc.perform(get("/api/data/visual")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.boardSummary").isNotEmpty())
            .andExpect(jsonPath("$.data.historicalWordCloud.length()").value(2))
            .andExpect(jsonPath("$.data.themeDistribution.length()").value(2))
            .andExpect(jsonPath("$.data.themeTable.length()").value(1))
            .andExpect(jsonPath("$.data.themeTable[0].representativeBooks[0].bookName").value("Brain City King"))
            .andExpect(jsonPath("$.data.hotBooks[0].bookName").value("Brain City King"))
            .andExpect(jsonPath("$.data.insightCards[0].label").value("Lead lane"))
            .andExpect(jsonPath("$.data.snapshotComparisons[0].leadBookName").value("Brain City King"))
            .andExpect(jsonPath("$.data.trendPreview").value("Urban-brain continues to dominate this board."))
            .andExpect(jsonPath("$.data.detailContent").value("Detailed board trend analysis for the last three snapshots."));
    }

    @Test
    void shouldSkipLatestBrokenTrendVisualResultAndUsePreviousStructuredVersion() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        jdbcTemplate.update("""
            INSERT INTO analysis_result
                (id, user_id, platform, book_id, channel_code, board_code, snapshot_id, analysis_type, chapter_count,
                 prompt_config_id, model_name, result_content, result_json, token_used, cost_time, create_time, update_time, deleted)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TIMESTAMP '2026-03-20 13:30:00', TIMESTAMP '2026-03-20 13:30:00', ?)
            """,
            3999L,
            1L,
            "fanqie",
            1001L,
            "male-new",
            "urban-brain",
            6001L,
            "theme",
            0,
            4L,
            "dify",
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
                """,
            33,
            120L,
            0
        );

        mockMvc.perform(get("/api/data/visual")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.boardSummary").isNotEmpty())
            .andExpect(jsonPath("$.data.historicalWordCloud.length()").value(2))
            .andExpect(jsonPath("$.data.themeDistribution.length()").value(2))
            .andExpect(jsonPath("$.data.themeTable.length()").value(2))
            .andExpect(jsonPath("$.data.hotBooks.length()").value(1))
            .andExpect(jsonPath("$.data.snapshotComparisons.length()").value(3))
            .andExpect(jsonPath("$.data.detailContent").value("Detailed board trend analysis for the last three snapshots."));
    }

    @Test
    void shouldNormalizeLegacyTrendFieldNamesFromEmbeddedJson() throws Exception {
        String token = loginAndGetToken("admin", "admin123");
        String legacyThemeJson = """
            {
              "summary": {
                "platform": "fanqie",
                "channel": "male-new",
                "board": "urban-brain",
                "coreTrend": "This board is shifting toward urban-brain hybrid hooks."
              },
              "historicalWordCloud": [
                {"word": "urban-brain", "count": 18, "percentage": "75.0%"}
              ],
              "themeTable": [
                {
                  "theme": "urban-brain hybrid",
                  "count": 3,
                  "percentage": "50.0%",
                  "top3Examples": ["Brain City King (stable #1)"],
                  "trend": "rising"
                }
              ],
              "hotBooks": [
                {
                  "title": "Brain City King",
                  "rankTrend": ["S3#1", "S2#1"],
                  "coreEmotion": "Rule-breaking payoff"
                }
              ]
            }
            """;
        String degradedStoredJson = """
            {
              "analysisType":"theme",
              "summary":"",
              "boardSummary":"",
              "trendPreview":%s,
              "detailContent":%s,
              "historicalWordCloud":[],
              "themeDistribution":[],
              "themeTable":[],
              "hotBooks":[],
              "insightCards":[],
              "snapshotComparisons":[],
              "comparisonSummary":"",
              "historyAnalysisCount":3
            }
            """.formatted(jsonStringLiteral(legacyThemeJson), jsonStringLiteral(legacyThemeJson));

        jdbcTemplate.update(
            "UPDATE analysis_result SET result_json = ?, result_content = ? WHERE id = ?",
            degradedStoredJson,
            legacyThemeJson,
            3004L
        );

        mockMvc.perform(get("/api/data/visual")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.boardSummary").value("This board is shifting toward urban-brain hybrid hooks."))
            .andExpect(jsonPath("$.data.historicalWordCloud[0].name").value("urban-brain"))
            .andExpect(jsonPath("$.data.historicalWordCloud[0].value").value(18))
            .andExpect(jsonPath("$.data.themeDistribution[0].theme").value("urban-brain hybrid"))
            .andExpect(jsonPath("$.data.themeDistribution[0].ratio").value(50.0))
            .andExpect(jsonPath("$.data.themeTable[0].representativeBooks[0].bookName").value("Brain City King"))
            .andExpect(jsonPath("$.data.hotBooks[0].bookName").value("Brain City King"))
            .andExpect(jsonPath("$.data.hotBooks[0].reason").value("Rule-breaking payoff"))
            .andExpect(jsonPath("$.data.trendPreview").value("This board is shifting toward urban-brain hybrid hooks."));
    }

    @Test
    void shouldReturnFallbackVisualDataWhenOnlyOneSnapshotIsAvailable() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        Long boardId = jdbcTemplate.queryForObject(
            "SELECT id FROM rank_board WHERE platform = ? AND channel_code = ? AND board_code = ? AND deleted = 0",
            Long.class,
            "fanqie",
            "male-new",
            "urban-brain"
        );
        Long latestSnapshotId = jdbcTemplate.queryForObject(
            "SELECT id FROM rank_snapshot WHERE rank_board_id = ? AND deleted = 0 ORDER BY snapshot_time DESC LIMIT 1",
            Long.class,
            boardId
        );

        jdbcTemplate.update(
            "UPDATE rank_snapshot SET deleted = 1 WHERE rank_board_id = ? AND id <> ?",
            boardId,
            latestSnapshotId
        );
        jdbcTemplate.update(
            "UPDATE analysis_result SET deleted = 1 WHERE platform = ? AND channel_code = ? AND board_code = ? AND analysis_type = ? AND deleted = 0",
            "fanqie",
            "male-new",
            "urban-brain",
            "theme"
        );

        mockMvc.perform(get("/api/data/visual")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.sourceSnapshotCount").value(1))
            .andExpect(jsonPath("$.data.historyAnalysisCount").value(1))
            .andExpect(jsonPath("$.data.latestSnapshots.length()").value(1))
            .andExpect(jsonPath("$.data.boardSummary").isEmpty())
            .andExpect(jsonPath("$.data.historicalWordCloud.length()").value(0))
            .andExpect(jsonPath("$.data.themeDistribution.length()").value(0))
            .andExpect(jsonPath("$.data.themeTable.length()").value(0))
            .andExpect(jsonPath("$.data.hotBooks.length()").value(0))
            .andExpect(jsonPath("$.data.insightCards.length()").value(0))
            .andExpect(jsonPath("$.data.snapshotComparisons.length()").value(0))
            .andExpect(jsonPath("$.data.comparisonSummary").isEmpty())
            .andExpect(jsonPath("$.data.trendPreview").isEmpty());
    }

    @Test
    void shouldReturnBoardScopedTrendAnalysis() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/analysis/trend")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("channelCode", "male-new")
                .param("boardCode", "urban-brain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisType").value("theme"))
            .andExpect(jsonPath("$.data.platform").value("fanqie"))
            .andExpect(jsonPath("$.data.channelCode").value("male-new"))
            .andExpect(jsonPath("$.data.boardCode").value("urban-brain"))
            .andExpect(jsonPath("$.data.boardName").isNotEmpty())
            .andExpect(jsonPath("$.data.sourceSnapshotCount").value(3))
            .andExpect(jsonPath("$.data.resultJson.analysisType").value("theme"))
            .andExpect(jsonPath("$.data.resultJson.boardSummary").isNotEmpty())
            .andExpect(jsonPath("$.data.resultJson.historicalWordCloud").isArray())
            .andExpect(jsonPath("$.data.resultJson.historicalWordCloud").isNotEmpty())
            .andExpect(jsonPath("$.data.resultJson.themeDistribution").isArray())
            .andExpect(jsonPath("$.data.resultJson.themeDistribution").isNotEmpty())
            .andExpect(jsonPath("$.data.resultJson.themeTable[0].representativeBooks").isArray())
            .andExpect(jsonPath("$.data.resultJson.hotBooks[0].rankNo").value(1))
            .andExpect(jsonPath("$.data.resultJson.summary").isNotEmpty())
            .andExpect(jsonPath("$.data.resultContent").isNotEmpty());
    }

    @Test
    void shouldContainSharedRankFoundationSeedData() {
        Integer boardCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM rank_board WHERE platform = ? AND channel_code = ? AND board_code = ? AND deleted = 0",
            Integer.class,
            "fanqie",
            "male-new",
            "urban-brain"
        );
        assertEquals(1, boardCount);

        Long boardId = jdbcTemplate.queryForObject(
            "SELECT id FROM rank_board WHERE platform = ? AND channel_code = ? AND board_code = ? AND deleted = 0",
            Long.class,
            "fanqie",
            "male-new",
            "urban-brain"
        );

        Integer snapshotCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM rank_snapshot WHERE rank_board_id = ? AND deleted = 0",
            Integer.class,
            boardId
        );
        assertEquals(3, snapshotCount);

        Integer rankRowWithSnapshotCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM crawl_rank WHERE platform = ? AND channel_code = ? AND board_code = ? AND snapshot_id IS NOT NULL AND deleted = 0",
            Integer.class,
            "fanqie",
            "male-new",
            "urban-brain"
        );
        assertTrue(rankRowWithSnapshotCount != null && rankRowWithSnapshotCount > 0);

        Integer promptConfigContractCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM prompt_config " +
                "WHERE prompt_type = ? " +
                "AND output_json_schema IS NOT NULL AND output_json_schema <> '' " +
                "AND output_example_json IS NOT NULL AND output_example_json <> '' " +
                "AND post_process_type IS NOT NULL AND post_process_type <> '' " +
                "AND parse_config_json IS NOT NULL AND parse_config_json <> '' " +
                "AND deleted = 0",
            Integer.class,
            "theme"
        );
        assertEquals(1, promptConfigContractCount);
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

    private String jsonStringLiteral(String value) {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n") + "\"";
    }
}

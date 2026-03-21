package com.novelanalyzer.modules.data;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
    void shouldReturn400WhenSystemConfigKeyMissing() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/config/system")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void shouldReturn400WhenTrendPlatformBlank() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/analysis/trend")
                .header("Authorization", "Bearer " + token)
                .param("platform", " ")
                .param("category", "male-hot-a"))
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
    void shouldReturnHistoryAndVisualData() throws Exception {
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
                .param("platform", "fanqie"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisTypeDistribution.length()").value(4))
            .andExpect(jsonPath("$.data.rankCategoryDistribution.length()").value(2))
            .andExpect(jsonPath("$.data.latestSnapshots.length()").value(3))
            .andExpect(jsonPath("$.data.wordCloud.length()").value(2))
            .andExpect(jsonPath("$.data.themeTable.length()").value(2))
            .andExpect(jsonPath("$.data.comparisonSummary").isNotEmpty())
            .andExpect(jsonPath("$.data.snapshotComparisons.length()").value(3));
    }

    @Test
    void shouldReturnTrendAnalysis() throws Exception {
        String token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/analysis/trend")
                .header("Authorization", "Bearer " + token)
                .param("platform", "fanqie")
                .param("category", "male-hot-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisType").value("theme"))
            .andExpect(jsonPath("$.data.platform").value("fanqie"))
            .andExpect(jsonPath("$.data.category").value("male-hot-a"))
            .andExpect(jsonPath("$.data.sourceSnapshotCount").value(3))
            .andExpect(jsonPath("$.data.resultJson.analysisType").value("theme"))
            .andExpect(jsonPath("$.data.resultContent").isNotEmpty());
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
}

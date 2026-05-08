package com.novelanalyzer.modules.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:prompt_publish_api;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class PromptPublishIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExposeAdminPublishAndForbidUserPublish() throws Exception {
        String adminToken = loginAndGetToken("admin", "admin123");
        String userToken = loginAndGetToken("writer", "writer123");

        mockMvc.perform(post("/api/config/prompt/system/publish")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"publishNote":"api publish","selections":[
                      {"promptType":"deconstruct","promptName":"default","promptConfigId":1},
                      {"promptType":"structure","promptName":"default","promptConfigId":2},
                      {"promptType":"plot","promptName":"default","promptConfigId":3},
                      {"promptType":"theme","promptName":"default","promptConfigId":4}
                    ]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.versionNo").value(2))
            .andExpect(jsonPath("$.data.items.length()").value(4));

        mockMvc.perform(post("/api/config/prompt/system/publish")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"publishNote":"user publish","selections":[
                      {"promptType":"deconstruct","promptName":"default","promptConfigId":1},
                      {"promptType":"structure","promptName":"default","promptConfigId":2},
                      {"promptType":"plot","promptName":"default","promptConfigId":3},
                      {"promptType":"theme","promptName":"default","promptConfigId":4}
                    ]}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldKeepUserCopyEndpointSeparateFromAdminPromptWrites() throws Exception {
        String userToken = loginAndGetToken("writer", "writer123");

        mockMvc.perform(post("/api/config/prompt/user/copy-from-global")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"promptType":"deconstruct","sourcePromptConfigId":1,"copyName":"writer-copy"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.promptType").value("deconstruct"))
            .andExpect(jsonPath("$.data.scopeType").value("USER_COPY"));
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
}

package com.novelanalyzer.modules.security;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:phase2db;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
    }
)
@AutoConfigureMockMvc
@Sql(
    scripts = {"classpath:sql/phase2-schema-h2.sql", "classpath:sql/phase2-data-h2.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class Phase2SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void shouldReturn401WhenNoToken() throws Exception {
        mockMvc.perform(get("/api/secure/admin/ping"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldReturn403WhenRoleNotEnough() throws Exception {
        String userToken = loginAndGetToken("writer", "writer123");
        mockMvc.perform(get("/api/secure/admin/ping")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldReturn429AndWriteLogWhenRateLimited() throws Exception {
        String adminToken = loginAndGetToken("admin", "admin123");
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/secure/user/ping")
                    .with(remoteAddr("127.0.9.9"))
                    .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(get("/api/secure/user/ping")
                .with(remoteAddr("127.0.9.9"))
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM sys_operation_log WHERE operation_type = 'RATE_LIMIT'",
            Integer.class
        );
        assertThat(count).isNotNull();
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void shouldSetRefreshCookieWhenLoginSucceeds() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        assertThat(setCookie).contains("refresh_token=");
    }

    @Test
    void shouldRejectProtectedRequestWhenSessionRevoked() throws Exception {
        String adminToken = loginAndGetToken("admin", "admin123");
        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/secure/user/ping")
                .with(remoteAddr("127.0.0.2"))
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldRejectProtectedRequestWhenSessionKicked() throws Exception {
        MvcResult firstLogin = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\",\"deviceLabel\":\"Device-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();
        String firstToken = JsonPath.read(firstLogin.getResponse().getContentAsString(), "$.data.accessToken");

        for (int i = 2; i <= 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"admin123\",\"deviceLabel\":\"Device-" + i + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(get("/api/secure/user/ping")
                .with(remoteAddr("127.0.0.3"))
                .header("Authorization", "Bearer " + firstToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldRehydrateSessionFromMysqlWhenRedisSessionStateMissing() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        String accessToken = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.accessToken");
        String sessionId = jwtUtils.parseClaims(accessToken, authProperties.getJwtSecret()).get("sid", String.class);
        String userSessionsKey = "auth:user:sessions:1";
        assertThat(sessionId).isNotBlank();

        stringRedisTemplate.delete("auth:session:" + sessionId);
        stringRedisTemplate.opsForZSet().remove(userSessionsKey, sessionId);

        mockMvc.perform(get("/api/secure/user/ping")
                .with(remoteAddr("127.0.9.8"))
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        String cachedUserId = (String) stringRedisTemplate.opsForHash().get("auth:session:" + sessionId, "userId");
        Double zsetScore = stringRedisTemplate.opsForZSet().score(userSessionsKey, sessionId);
        assertThat(cachedUserId).isEqualTo("1");
        assertThat(zsetScore).isNotNull();
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

    private RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}

package com.novelanalyzer.modules.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:passwordriskdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "app.security.rate-limit-per-minute=100",
        "app.security.password-login-phone-window-seconds=600",
        "app.security.password-login-phone-max-failures=2",
        "app.security.password-login-ip-window-seconds=600",
        "app.security.password-login-ip-max-failures=3",
        "app.security.password-login-phone-ip-window-seconds=600",
        "app.security.password-login-phone-ip-max-failures=2",
        "app.security.password-login-cooldown-seconds=300"
    }
)
@AutoConfigureMockMvc
@Sql(
    scripts = {"classpath:sql/phase2-schema-h2.sql", "classpath:sql/phase2-data-h2.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class PasswordLoginRiskControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
    void shouldBlockRepeatedWrongPasswordsForSamePhoneAndIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/login/password")
                    .with(remoteAddr("198.18.0.21"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        }

        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.21"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void shouldBlockDistributedPasswordAttemptsAgainstSamePhone() throws Exception {
        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.31"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.32"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.33"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void shouldBlockSingleIpSweepingMultiplePhones() throws Exception {
        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.41"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.41"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138001\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.41"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138999\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("198.18.0.41"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138998\",\"password\":\"wrong\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));
    }

    private RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}

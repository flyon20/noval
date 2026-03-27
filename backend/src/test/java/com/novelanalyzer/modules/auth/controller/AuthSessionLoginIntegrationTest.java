package com.novelanalyzer.modules.auth.controller;

import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:authsessionlogin;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "app.auth.demo-username=demo-admin",
        "app.auth.demo-password=demo123"
    }
)
@AutoConfigureMockMvc
@Sql(
    scripts = {"classpath:sql/phase2-schema-h2.sql", "classpath:sql/phase2-data-h2.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class AuthSessionLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistActiveSessionWhenLoginSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\",\"deviceLabel\":\"Chrome on Windows\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        Integer sessionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user_session WHERE user_id = ? AND status = ? AND deleted = 0",
            Integer.class,
            1L,
            AuthSessionStatus.ACTIVE
        );
        String deviceLabel = jdbcTemplate.queryForObject(
            "SELECT device_label FROM sys_user_session WHERE user_id = ? ORDER BY id DESC LIMIT 1",
            String.class,
            1L
        );
        LocalDateTime refreshExpireTime = jdbcTemplate.queryForObject(
            "SELECT refresh_expire_time FROM sys_user_session WHERE user_id = ? ORDER BY id DESC LIMIT 1",
            LocalDateTime.class,
            1L
        );

        assertThat(sessionCount).isEqualTo(1);
        assertThat(deviceLabel).isEqualTo("Chrome on Windows");
        assertThat(refreshExpireTime).isAfter(LocalDateTime.now());
    }
}

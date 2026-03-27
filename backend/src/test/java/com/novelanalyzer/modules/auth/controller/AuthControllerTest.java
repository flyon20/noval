package com.novelanalyzer.modules.auth.controller;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class AuthControllerTest {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private AuthProperties authProperties;

    @Test
    void shouldLoginRefreshAndLogoutSuccessfully() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        String accessToken = JsonPath.read(responseBody, "$.data.accessToken");
        String firstRefreshToken = extractRefreshToken(loginResult);
        String originalSessionId = jwtUtils.parseClaims(accessToken, authProperties.getJwtSecret()).get("sid", String.class);

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                .cookie(refreshCookie(firstRefreshToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn();

        String refreshedAccessToken = JsonPath.read(refreshResult.getResponse().getContentAsString(), "$.data.accessToken");
        String secondRefreshToken = extractRefreshToken(refreshResult);
        String refreshedSessionId = jwtUtils.parseClaims(refreshedAccessToken, authProperties.getJwtSecret()).get("sid", String.class);

        assertThat(originalSessionId).isNotBlank();
        assertThat(refreshedSessionId).isEqualTo(originalSessionId);
        assertThat(secondRefreshToken).isNotBlank();
        assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                .cookie(refreshCookie(firstRefreshToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        MvcResult logoutResult = mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + refreshedAccessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        String logoutCookie = logoutResult.getResponse().getHeader("Set-Cookie");
        assertThat(logoutCookie).contains(REFRESH_COOKIE_NAME + "=");
        assertThat(logoutCookie).contains("Max-Age=0");

        mockMvc.perform(post("/api/auth/refresh")
                .cookie(refreshCookie(secondRefreshToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/secure/user/ping")
                .header("Authorization", "Bearer " + refreshedAccessToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldKickOldestActiveSessionWhenFourthDeviceLogsIn() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"admin123\",\"deviceLabel\":\"Device-" + i + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        }

        Integer activeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user_session WHERE user_id = 1 AND status = 1 AND deleted = 0",
            Integer.class
        );
        Integer kickedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user_session WHERE user_id = 1 AND status = 4 AND deleted = 0",
            Integer.class
        );
        List<String> kickedDevices = jdbcTemplate.queryForList(
            "SELECT device_label FROM sys_user_session WHERE user_id = 1 AND status = 4 AND deleted = 0",
            String.class
        );

        assertThat(activeCount).isEqualTo(3);
        assertThat(kickedCount).isEqualTo(1);
        assertThat(kickedDevices).containsExactly("Device-1");
    }

    @Test
    void shouldIssueRefreshCookieOnLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn();

        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        assertThat(setCookie).contains(REFRESH_COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("Path=/api/auth");
    }

    @Test
    void shouldRejectWrongPasswordWithHelpfulMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("密码错误，请重新输入"));
    }

    @Test
    void shouldRejectUnknownUsernameWithHelpfulMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"missing-user\",\"password\":\"Password123\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("用户名不存在，请先注册"));
    }

    @Test
    void shouldRegisterUserAndReturnToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"new-user\",\"password\":\"Password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        Integer userCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE username = ?",
            Integer.class,
            "new-user"
        );
        String roleCode = jdbcTemplate.queryForObject(
            """
            SELECT r.role_code
            FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id = r.id
            INNER JOIN sys_user u ON u.id = ur.user_id
            WHERE u.username = ?
            LIMIT 1
            """,
            String.class,
            "new-user"
        );

        org.assertj.core.api.Assertions.assertThat(userCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(roleCode).isEqualTo("USER");
    }

    @Test
    void shouldRejectRegisterWhenUsernameExists() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"Password123\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("用户名已存在，请更换后重试"));
    }

    @Test
    void shouldRejectRegisterWhenPasswordIsTooWeak() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"weak-user\",\"password\":\"secret123\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("密码需至少 8 位，且包含大写字母、小写字母和数字"));
    }

    @Test
    void shouldReturnSpecificValidationMessageWhenRegisterPasswordMissing() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"missing-password\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("password is required"));
    }

    @Test
    void shouldRejectConfiguredDemoCredentialWhenDemoDisabled() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo-admin\",\"password\":\"demo123\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldRejectRefreshWhenUserDisabledAfterLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        String refreshToken = extractRefreshToken(loginResult);
        jdbcTemplate.update("UPDATE sys_user SET status = 0 WHERE id = 1");

        mockMvc.perform(post("/api/auth/refresh")
                .cookie(refreshCookie(refreshToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldRejectLogoutWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldRejectRefreshWhenCookieMissing() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("refresh token is required"));
    }

    @Test
    void shouldBlockRefreshFromBlacklistedIpEvenThoughEndpointIsWhitelisted() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .with(remoteAddr("127.0.0.1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        String refreshToken = extractRefreshToken(loginResult);
        jdbcTemplate.update(
            "INSERT INTO sys_ip_blacklist (ip_address, reason, expire_time, status) VALUES (?, ?, DATEADD('DAY', 1, CURRENT_TIMESTAMP), 1)",
            "127.0.0.1",
            "manual block"
        );

        mockMvc.perform(post("/api/auth/refresh")
                .with(remoteAddr("127.0.0.1"))
                .cookie(refreshCookie(refreshToken)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldIgnoreSpoofedForwardedIpWhenProxyNotTrusted() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .with(remoteAddr("127.0.0.1"))
                .header("X-Forwarded-For", "198.51.100.77")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        String loginIp = jdbcTemplate.queryForObject(
            "SELECT login_ip FROM sys_login_log ORDER BY id DESC LIMIT 1",
            String.class
        );

        org.assertj.core.api.Assertions.assertThat(loginIp).isEqualTo("127.0.0.1");
    }

    private RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockCookie refreshCookie(String refreshToken) {
        return new MockCookie(REFRESH_COOKIE_NAME, refreshToken);
    }

    private String extractRefreshToken(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        int prefixIndex = setCookie.indexOf(REFRESH_COOKIE_NAME + "=");
        assertThat(prefixIndex).isGreaterThanOrEqualTo(0);
        int valueStart = prefixIndex + REFRESH_COOKIE_NAME.length() + 1;
        int valueEnd = setCookie.indexOf(';', valueStart);
        if (valueEnd < 0) {
            valueEnd = setCookie.length();
        }
        return setCookie.substring(valueStart, valueEnd);
    }
}

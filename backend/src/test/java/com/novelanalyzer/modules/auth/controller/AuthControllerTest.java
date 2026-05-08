package com.novelanalyzer.modules.auth.controller;

import com.jayway.jsonpath.JsonPath;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.service.AuthSessionFlushScheduler;
import com.novelanalyzer.modules.auth.service.SmsAuthService;
import com.novelanalyzer.modules.auth.service.TurnstileService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "app.auth.demo-username=demo-admin",
        "app.auth.demo-password=demo123"
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AuthSessionFlushScheduler authSessionFlushScheduler;

    @MockBean
    private SmsAuthService smsAuthService;

    @MockBean
    private TurnstileService turnstileService;

    @Test
    void shouldLoginRefreshAndLogoutSuccessfullyWithPhonePassword() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"admin123\"}"))
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

        Cookie logoutCookie = logoutResult.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertThat(logoutCookie).isNotNull();
        assertThat(logoutCookie.getMaxAge()).isZero();
        assertThat(logoutCookie.isHttpOnly()).isTrue();

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
    void shouldIssueRefreshCookieOnPhonePasswordLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"admin123\"}"))
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
    void shouldDropSecureFlagForLoopbackHttpOrigin() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login/password")
                .header("Origin", "http://127.0.0.1:5173")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        assertThat(setCookie).contains(REFRESH_COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).doesNotContain("Secure");
        assertThat(setCookie).contains("SameSite=Strict");
    }

    @Test
    void shouldRejectWrongPasswordWithHelpfulMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("密码错误，请重新输入"));
    }

    @Test
    void shouldRejectUnknownPhoneWithHelpfulMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138999\",\"password\":\"Password123\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("手机号未注册，请先注册"));
    }

    @Test
    void shouldRejectRefreshWhenCookieMissing() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("refresh token is required"));
    }

    @Test
    void shouldGrantAdminRoleToBootstrapPhoneOnPasswordLogin() throws Exception {
        jdbcTemplate.update(
            """
            INSERT INTO sys_user (id, username, password, phone, phone_verified, status, deleted, password_updated_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """,
            3L,
            "bootstrap-admin",
            "{noop}Password123",
            "15599316908",
            1,
            1,
            0
        );
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", 3L, 2L);

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"15599316908\",\"password\":\"Password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        String accessToken = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.accessToken");
        String roles = jwtUtils.parseClaims(accessToken, authProperties.getJwtSecret()).get("roles", String.class);
        Integer adminRoleCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user_role WHERE user_id = ? AND role_id = ?",
            Integer.class,
            3L,
            1L
        );

        assertThat(roles).contains("ADMIN");
        assertThat(adminRoleCount).isEqualTo(1);
    }

    @Test
    void shouldRejectSmsSendWhenTurnstileTokenMissing() throws Exception {
        doThrow(new BusinessException(ResultCode.BAD_REQUEST, "请完成人机校验后再发送验证码"))
            .when(turnstileService)
            .assertSmsSendPassed(isNull(), anyString());

        mockMvc.perform(post("/api/auth/sms/send")
                .with(remoteAddr("127.0.0.1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"bizType\":\"REGISTER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void shouldReturnDebugVerifyCodeForLoopbackSmsSend() throws Exception {
        when(smsAuthService.sendVerifyCode(anyString(), anyString(), anyString()))
            .thenReturn(new SmsAuthService.SendResult("fe0orl", "out-id-001"));

        mockMvc.perform(post("/api/auth/sms/send")
                .with(remoteAddr("127.0.0.1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"bizType\":\"REGISTER\",\"turnstileToken\":\"test-token\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.debugVerifyCode").value("fe0orl"))
            .andExpect(jsonPath("$.data.smsOutId").value("out-id-001"));

        verify(smsAuthService).sendVerifyCode("13800138000", "REGISTER", "127.0.0.1");
    }

    @Test
    void shouldBlockRefreshFromBlacklistedIpEvenThoughEndpointIsWhitelisted() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login/password")
                .with(remoteAddr("127.0.0.1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"admin123\"}"))
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
    void shouldFlushDirtySessionActivityToMysql() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13800138000\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn();

        String accessToken = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.data.accessToken");
        String sessionId = jwtUtils.parseClaims(accessToken, authProperties.getJwtSecret()).get("sid", String.class);
        assertThat(sessionId).isNotBlank();

        jdbcTemplate.update(
            "UPDATE sys_user_session SET last_active_time = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) WHERE session_id = ?",
            sessionId
        );
        Timestamp oldLastActive = jdbcTemplate.queryForObject(
            "SELECT last_active_time FROM sys_user_session WHERE session_id = ?",
            Timestamp.class,
            sessionId
        );
        assertThat(oldLastActive).isNotNull();

        mockMvc.perform(get("/api/secure/user/ping")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        assertThat(stringRedisTemplate.opsForSet().isMember("auth:session:dirty", sessionId)).isTrue();

        authSessionFlushScheduler.flushDirtySessions();

        assertThat(stringRedisTemplate.opsForSet().isMember("auth:session:dirty", sessionId)).isFalse();
        Timestamp newLastActive = jdbcTemplate.queryForObject(
            "SELECT last_active_time FROM sys_user_session WHERE session_id = ?",
            Timestamp.class,
            sessionId
        );
        assertThat(newLastActive).isNotNull();
        assertThat(newLastActive.toLocalDateTime()).isAfter(oldLastActive.toLocalDateTime());
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

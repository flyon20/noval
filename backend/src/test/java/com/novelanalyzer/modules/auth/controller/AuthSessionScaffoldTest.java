package com.novelanalyzer.modules.auth.controller;

import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "app.auth.access-token-expire-seconds=900",
        "app.auth.refresh-token-expire-seconds=604800",
        "app.auth.session-max-devices=3",
        "app.auth.refresh-cookie-name=refresh_token",
        "app.auth.refresh-cookie-path=/api/auth",
        "app.auth.refresh-cookie-secure=true",
        "app.auth.refresh-cookie-same-site=Strict"
    }
)
@Sql(
    scripts = {"classpath:sql/phase2-schema-h2.sql", "classpath:sql/phase2-data-h2.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class AuthSessionScaffoldTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthProperties authProperties;

    @Test
    void shouldLoadUserSessionTableFromSharedSchema() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user_session", Integer.class);
        assertThat(total).isZero();
    }

    @Test
    void shouldBindSessionAuthProperties() {
        assertThat(authProperties.getAccessTokenExpireSeconds()).isEqualTo(900L);
        assertThat(authProperties.getRefreshTokenExpireSeconds()).isEqualTo(604800L);
        assertThat(authProperties.getSessionMaxDevices()).isEqualTo(3);
        assertThat(authProperties.getRefreshCookieName()).isEqualTo("refresh_token");
        assertThat(authProperties.getRefreshCookiePath()).isEqualTo("/api/auth");
        assertThat(authProperties.isRefreshCookieSecure()).isTrue();
        assertThat(authProperties.getRefreshCookieSameSite()).isEqualTo("Strict");
    }

    @Test
    void shouldKeepDeviceLabelOptionalInLoginRequest() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        assertThat(request.getDeviceLabel()).isNull();

        request.setDeviceLabel("Chrome on Windows");
        assertThat(request.getDeviceLabel()).isEqualTo("Chrome on Windows");
    }
}

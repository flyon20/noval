package com.novelanalyzer.modules.auth.controller;

import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    }
)
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
class AuthSessionScaffoldTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private Validator validator;

    @Test
    void shouldLoadUserSessionTableFromSharedSchema() {
        Integer tableCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_USER_SESSION'",
            Integer.class
        );
        assertThat(tableCount).isEqualTo(1);

        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user_session", Integer.class);
        assertThat(total).isZero();
    }

    @Test
    void shouldBindSessionAuthPropertiesFromApplicationDefaults() {
        assertThat(authProperties.getAccessTokenExpireSeconds()).isEqualTo(900L);
        assertThat(authProperties.getRefreshTokenExpireSeconds()).isEqualTo(604800L);
        assertThat(authProperties.getSessionMaxDevices()).isEqualTo(3);
        assertThat(authProperties.getRefreshCookieName()).isEqualTo("refresh_token");
        assertThat(authProperties.getRefreshCookiePath()).isEqualTo("/api/auth");
        assertThat(authProperties.isRefreshCookieSecure()).isTrue();
        assertThat(authProperties.getRefreshCookieSameSite()).isEqualTo("Strict");
    }

    @Test
    void shouldValidateDeviceLabelLengthConstraint() {
        LoginRequest request = new LoginRequest();
        request.setPhone("13800138000");
        request.setPassword("admin123");
        assertThat(request.getDeviceLabel()).isNull();

        request.setDeviceLabel("a".repeat(101));
        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(request);
        assertThat(violations)
            .anyMatch(v -> "deviceLabel".equals(v.getPropertyPath().toString()));

        request.setDeviceLabel("Chrome on Windows");
        Set<ConstraintViolation<LoginRequest>> okViolations = validator.validate(request);
        assertThat(okViolations)
            .noneMatch(v -> "deviceLabel".equals(v.getPropertyPath().toString()));
    }
}

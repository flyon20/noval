package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthConfigValidatorTest {

    @Test
    void shouldRejectBlankJwtSecret() {
        AuthProperties properties = new AuthProperties();
        applyValidSessionDefaults(properties);
        properties.setJwtSecret("");

        AuthConfigValidator validator = new AuthConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT secret");
    }

    @Test
    void shouldRejectTooShortJwtSecret() {
        AuthProperties properties = new AuthProperties();
        applyValidSessionDefaults(properties);
        properties.setJwtSecret("short-secret");

        AuthConfigValidator validator = new AuthConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT secret");
    }

    @Test
    void shouldAcceptSecureJwtSecret() {
        AuthProperties properties = new AuthProperties();
        applyValidSessionDefaults(properties);
        properties.setJwtSecret("secure-jwt-secret-with-enough-length-1234567890");

        AuthConfigValidator validator = new AuthConfigValidator(properties);

        assertThatNoException().isThrownBy(validator::validate);
    }

    private void applyValidSessionDefaults(AuthProperties properties) {
        properties.setDemoEnabled(false);
        properties.setAccessTokenExpireSeconds(900);
        properties.setRefreshTokenExpireSeconds(604800);
        properties.setSessionMaxDevices(3);
        properties.setRefreshCookieName("refresh_token");
        properties.setRefreshCookiePath("/api/auth");
        properties.setRefreshCookieSecure(true);
        properties.setRefreshCookieSameSite("Strict");
    }
}

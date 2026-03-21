package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthConfigValidatorTest {

    @Test
    void shouldRejectBlankJwtSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("");
        properties.setDemoEnabled(false);
        properties.setAccessTokenExpireSeconds(7200);

        AuthConfigValidator validator = new AuthConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT secret");
    }

    @Test
    void shouldRejectTooShortJwtSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("short-secret");
        properties.setDemoEnabled(false);
        properties.setAccessTokenExpireSeconds(7200);

        AuthConfigValidator validator = new AuthConfigValidator(properties);

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT secret");
    }

    @Test
    void shouldAcceptSecureJwtSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("secure-jwt-secret-with-enough-length-1234567890");
        properties.setDemoEnabled(false);
        properties.setAccessTokenExpireSeconds(7200);

        AuthConfigValidator validator = new AuthConfigValidator(properties);

        assertThatNoException().isThrownBy(validator::validate);
    }
}

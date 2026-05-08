package com.novelanalyzer.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class AuthConfigValidator {

    private static final int MIN_JWT_SECRET_LENGTH = 32;

    private final AuthProperties authProperties;

    public AuthConfigValidator(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @PostConstruct
    public void init() {
        validate();
    }

    public void validate() {
        String jwtSecret = authProperties.getJwtSecret();
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.trim().length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException("JWT secret must be configured and at least 32 characters long.");
        }
        if (authProperties.getAccessTokenExpireSeconds() <= 0) {
            throw new IllegalStateException("JWT access token expiration must be greater than 0.");
        }
        if (authProperties.getRefreshTokenExpireSeconds() <= 0) {
            throw new IllegalStateException("Refresh token expiration must be greater than 0.");
        }
        if (authProperties.getSessionMaxDevices() <= 0) {
            throw new IllegalStateException("Session max devices must be greater than 0.");
        }
        if (isBlank(authProperties.getRefreshCookieName())) {
            throw new IllegalStateException("Refresh cookie name must not be blank.");
        }
        if (isBlank(authProperties.getRefreshCookiePath())) {
            throw new IllegalStateException("Refresh cookie path must not be blank.");
        }
        if (isBlank(authProperties.getRefreshCookieSameSite())) {
            throw new IllegalStateException("Refresh cookie same-site must not be blank.");
        }
        if (!authProperties.isDemoEnabled()) {
            return;
        }
        if (isBlank(authProperties.getDemoUsername()) || isBlank(authProperties.getDemoPassword())) {
            throw new IllegalStateException("Demo auth requires both demo username and demo password.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

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

package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import com.novelanalyzer.modules.auth.vo.TokenResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AuthService {

    private final AuthProperties authProperties;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final AtomicReference<String> cachedEncodedPassword = new AtomicReference<>();

    public AuthService(AuthProperties authProperties, JwtUtils jwtUtils, PasswordEncoder passwordEncoder) {
        this.authProperties = authProperties;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = passwordEncoder;
    }

    public TokenResponse login(LoginRequest request) {
        validateUser(request.getUsername(), request.getPassword());
        return issueToken(request.getUsername());
    }

    public TokenResponse refresh(String token) {
        try {
            Claims claims = jwtUtils.parseClaims(token, authProperties.getJwtSecret());
            return issueToken(claims.getSubject());
        } catch (JwtException ex) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "token is required");
        }
    }

    private void validateUser(String username, String password) {
        if (!authProperties.getDemoUsername().equals(username)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "username or password is incorrect");
        }
        String encodedPassword = getOrInitEncodedPassword();
        if (!passwordEncoder.matches(password, encodedPassword)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "username or password is incorrect");
        }
    }

    private String getOrInitEncodedPassword() {
        String current = cachedEncodedPassword.get();
        if (current != null) {
            return current;
        }
        String encoded = passwordEncoder.encode(authProperties.getDemoPassword());
        cachedEncodedPassword.compareAndSet(null, encoded);
        return cachedEncodedPassword.get();
    }

    private TokenResponse issueToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        String accessToken = jwtUtils.generateToken(
            username,
            authProperties.getJwtSecret(),
            authProperties.getAccessTokenExpireSeconds(),
            claims
        );
        TokenResponse response = new TokenResponse();
        response.setAccessToken(accessToken);
        response.setTokenType("Bearer");
        response.setExpiresIn(authProperties.getAccessTokenExpireSeconds());
        return response;
    }
}


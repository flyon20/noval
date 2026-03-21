package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import com.novelanalyzer.modules.auth.model.AuthUserEntity;
import com.novelanalyzer.modules.auth.repository.AuthRepository;
import com.novelanalyzer.modules.auth.vo.TokenResponse;
import com.novelanalyzer.modules.security.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AuthService {

    private final AuthProperties authProperties;
    private final AuthRepository authRepository;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final AtomicReference<String> cachedEncodedPassword = new AtomicReference<>();

    public AuthService(AuthProperties authProperties,
                       AuthRepository authRepository,
                       JwtUtils jwtUtils,
                       PasswordEncoder passwordEncoder,
                       TokenBlacklistService tokenBlacklistService) {
        this.authProperties = authProperties;
        this.authRepository = authRepository;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public TokenResponse login(LoginRequest request, String loginIp) {
        AuthUserEntity dbUser = authRepository.findActiveUserByUsername(request.getUsername()).orElse(null);
        if (dbUser != null) {
            if (!passwordMatches(request.getPassword(), dbUser.getPassword())) {
                authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), loginIp, 0, "username or password is incorrect");
                throw new BusinessException(ResultCode.UNAUTHORIZED, "username or password is incorrect");
            }
            List<String> roleCodes = authRepository.findRoleCodesByUserId(dbUser.getId());
            authRepository.updateLastLoginTime(dbUser.getId());
            authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), loginIp, 1, "login success");
            return issueToken(dbUser.getId(), dbUser.getUsername(), roleCodes);
        }

        if (authProperties.isDemoEnabled()) {
            validateDemoUser(request.getUsername(), request.getPassword(), loginIp);
            return issueToken(0L, request.getUsername(), List.of("ADMIN"));
        }

        authRepository.insertLoginLog(null, request.getUsername(), loginIp, 0, "username or password is incorrect");
        throw new BusinessException(ResultCode.UNAUTHORIZED, "username or password is incorrect");
    }

    public TokenResponse refresh(String token) {
        if (tokenBlacklistService.isBlacklisted(token)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
        try {
            Claims claims = jwtUtils.parseClaims(token, authProperties.getJwtSecret());
            Long userId = claims.get("uid", Long.class);
            if (userId == null || userId <= 0) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
            }
            AuthUserEntity dbUser = authRepository.findActiveUserById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired"));
            List<String> roleCodes = authRepository.findRoleCodesByUserId(dbUser.getId());
            return issueToken(dbUser.getId(), dbUser.getUsername(), roleCodes);
        } catch (JwtException ex) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "token is required");
        }
        try {
            Claims claims = jwtUtils.parseClaims(token, authProperties.getJwtSecret());
            long expireSeconds = Math.max(1L, (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000L);
            tokenBlacklistService.blacklist(token, expireSeconds);
        } catch (JwtException ex) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
    }

    private void validateDemoUser(String username, String password, String loginIp) {
        if (!authProperties.getDemoUsername().equals(username)) {
            authRepository.insertLoginLog(null, username, loginIp, 0, "username or password is incorrect");
            throw new BusinessException(ResultCode.UNAUTHORIZED, "username or password is incorrect");
        }
        String encodedPassword = getOrInitEncodedPassword();
        if (!passwordEncoder.matches(password, encodedPassword)) {
            authRepository.insertLoginLog(null, username, loginIp, 0, "username or password is incorrect");
            throw new BusinessException(ResultCode.UNAUTHORIZED, "username or password is incorrect");
        }
        authRepository.insertLoginLog(0L, username, loginIp, 1, "login success(demo)");
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

    private boolean passwordMatches(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        if (encodedPassword.startsWith("{noop}")) {
            return rawPassword.equals(encodedPassword.substring("{noop}".length()));
        }
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    private TokenResponse issueToken(Long userId, String username, List<String> roleCodes) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("username", username);
        claims.put("roles", String.join(",", roleCodes));
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

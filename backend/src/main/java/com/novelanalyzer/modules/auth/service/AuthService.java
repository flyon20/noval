package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import com.novelanalyzer.modules.auth.dto.RegisterRequest;
import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import com.novelanalyzer.modules.auth.model.AuthUserEntity;
import com.novelanalyzer.modules.auth.repository.AuthRepository;
import com.novelanalyzer.modules.auth.repository.AuthSessionRepository;
import com.novelanalyzer.modules.auth.vo.TokenResponse;
import com.novelanalyzer.modules.auth.service.AuthSessionService.CreatedSession;
import com.novelanalyzer.modules.security.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final String LOGIN_FAILED_MESSAGE = "登录失败，请检查用户名和密码";
    private static final String USERNAME_NOT_FOUND_MESSAGE = "用户名不存在，请先注册";
    private static final String PASSWORD_INCORRECT_MESSAGE = "密码错误，请重新输入";
    private static final String USERNAME_EXISTS_MESSAGE = "用户名已存在，请更换后重试";
    private static final String ACCOUNT_DISABLED_MESSAGE = "账号不可用，请联系管理员";
    private static final String PASSWORD_RULE_MESSAGE = "密码需至少 8 位，且包含大写字母、小写字母和数字";
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT_PATTERN = Pattern.compile(".*\\d.*");
    private static final String KICKED_BY_NEW_LOGIN_REASON = "kicked by new login";

    private final AuthProperties authProperties;
    private final AuthRepository authRepository;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuthSessionService authSessionService;
    private final AuthSessionRepository authSessionRepository;
    private final RefreshTokenService refreshTokenService;
    private final AtomicReference<String> cachedEncodedPassword = new AtomicReference<>();

    public AuthService(AuthProperties authProperties,
                       AuthRepository authRepository,
                       JwtUtils jwtUtils,
                       PasswordEncoder passwordEncoder,
                       TokenBlacklistService tokenBlacklistService,
                       AuthSessionService authSessionService,
                       AuthSessionRepository authSessionRepository,
                       RefreshTokenService refreshTokenService) {
        this.authProperties = authProperties;
        this.authRepository = authRepository;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklistService = tokenBlacklistService;
        this.authSessionService = authSessionService;
        this.authSessionRepository = authSessionRepository;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public LoginResult login(LoginRequest request, String loginIp) {
        String username = normalizeUsername(request.getUsername());
        AuthUserEntity dbUser = authRepository.findUserByUsername(username).orElse(null);
        if (dbUser != null) {
            if (dbUser.getStatus() == null || dbUser.getStatus() != 1) {
                authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), loginIp, 0, ACCOUNT_DISABLED_MESSAGE);
                throw new BusinessException(ResultCode.UNAUTHORIZED, ACCOUNT_DISABLED_MESSAGE);
            }
            if (!passwordMatches(request.getPassword(), dbUser.getPassword())) {
                authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), loginIp, 0, PASSWORD_INCORRECT_MESSAGE);
                throw new BusinessException(ResultCode.UNAUTHORIZED, PASSWORD_INCORRECT_MESSAGE);
            }
            List<String> roleCodes = authRepository.findRoleCodesByUserId(dbUser.getId());
            authRepository.lockUserById(dbUser.getId());
            authRepository.updateLastLoginTime(dbUser.getId());
            authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), loginIp, 1, "login success");
            enforceDeviceLimit(dbUser.getId());
            CreatedSession session = authSessionService.createSession(dbUser.getId(), request.getDeviceLabel(), null, loginIp);
            return new LoginResult(
                issueToken(dbUser.getId(), dbUser.getUsername(), roleCodes, session.sessionId()),
                session.refreshToken()
            );
        }

        if (authProperties.isDemoEnabled()) {
            validateDemoUser(username, request.getPassword(), loginIp);
            return new LoginResult(issueToken(0L, username, List.of("ADMIN"), null), null);
        }

        authRepository.insertLoginLog(null, username, loginIp, 0, USERNAME_NOT_FOUND_MESSAGE);
        throw new BusinessException(ResultCode.UNAUTHORIZED, USERNAME_NOT_FOUND_MESSAGE);
    }

    @Transactional
    public TokenResponse register(RegisterRequest request, String registerIp) {
        String username = normalizeUsername(request.getUsername());
        validatePasswordRule(request.getPassword());
        if (authRepository.existsUserByUsername(username)) {
            authRepository.insertLoginLog(null, username, registerIp, 0, USERNAME_EXISTS_MESSAGE);
            throw new BusinessException(ResultCode.BAD_REQUEST, USERNAME_EXISTS_MESSAGE);
        }

        try {
            Long userId = authRepository.insertUser(username, passwordEncoder.encode(request.getPassword()));
            Long defaultRoleId = authRepository.findActiveRoleIdByCode("USER")
                .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "default role is not configured"));

            authRepository.insertUserRole(userId, defaultRoleId);
            List<String> roleCodes = authRepository.findRoleCodesByUserId(userId);
            authRepository.insertLoginLog(userId, username, registerIp, 1, "register success");
            CreatedSession session = authSessionService.createSession(userId, null, null, registerIp);
            return issueToken(userId, username, roleCodes, session.sessionId());
        } catch (DuplicateKeyException ex) {
            authRepository.insertLoginLog(null, username, registerIp, 0, USERNAME_EXISTS_MESSAGE);
            throw new BusinessException(ResultCode.BAD_REQUEST, USERNAME_EXISTS_MESSAGE);
        }
    }

    @Transactional
    public RefreshResult refresh(String refreshToken) {
        String refreshTokenHash = refreshTokenService.hashRefreshToken(refreshToken);
        var sessionOptional = authSessionService.findActiveSessionByRefreshTokenHash(refreshTokenHash);
        if (sessionOptional.isEmpty()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
        var session = sessionOptional.get();
        if (!refreshTokenHash.equals(session.getRefreshTokenHash())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
        AuthUserEntity dbUser = authRepository.findActiveUserById(session.getUserId())
            .orElseThrow(() -> new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired"));

        String nextRefreshToken = refreshTokenService.generateOpaqueRefreshToken();
        String nextRefreshTokenHash = refreshTokenService.hashRefreshToken(nextRefreshToken);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime refreshExpireTime = now.plusSeconds(authProperties.getRefreshTokenExpireSeconds());
        boolean updated = authSessionRepository.updateSessionOnRefresh(
            session.getSessionId(),
            refreshTokenHash,
            nextRefreshTokenHash,
            now,
            refreshExpireTime
        );
        if (!updated) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }

        authSessionService.rehydrateSessionBySessionId(session.getSessionId());
        List<String> roleCodes = authRepository.findRoleCodesByUserId(dbUser.getId());
        return new RefreshResult(issueToken(dbUser.getId(), dbUser.getUsername(), roleCodes, session.getSessionId()), nextRefreshToken);
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "token is required");
        }
        try {
            Claims claims = jwtUtils.parseClaims(token, authProperties.getJwtSecret());
            String sessionId = claims.get("sid", String.class);
            if (sessionId == null || sessionId.isBlank()) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
            }
            authSessionService.revokeSession(sessionId, AuthSessionStatus.REVOKED, "logout");
            long expireSeconds = Math.max(1L, (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000L);
            tokenBlacklistService.blacklist(token, expireSeconds);
        } catch (JwtException ex) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
    }

    private void validateDemoUser(String username, String password, String loginIp) {
        if (!authProperties.getDemoUsername().equals(username)) {
            authRepository.insertLoginLog(null, username, loginIp, 0, LOGIN_FAILED_MESSAGE);
            throw new BusinessException(ResultCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE);
        }
        String encodedPassword = getOrInitEncodedPassword();
        if (!passwordEncoder.matches(password, encodedPassword)) {
            authRepository.insertLoginLog(null, username, loginIp, 0, PASSWORD_INCORRECT_MESSAGE);
            throw new BusinessException(ResultCode.UNAUTHORIZED, PASSWORD_INCORRECT_MESSAGE);
        }
        authRepository.insertLoginLog(0L, username, loginIp, 1, "login success(demo)");
    }

    private void validatePasswordRule(String password) {
        String candidate = password == null ? "" : password.trim();
        if (candidate.length() < 8
            || !UPPERCASE_PATTERN.matcher(candidate).matches()
            || !LOWERCASE_PATTERN.matcher(candidate).matches()
            || !DIGIT_PATTERN.matcher(candidate).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, PASSWORD_RULE_MESSAGE);
        }
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
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

    private void enforceDeviceLimit(Long userId) {
        int maxDevices = Math.max(1, authProperties.getSessionMaxDevices());
        int activeSessionCount = authSessionRepository.findActiveSessionsByUserIdForUpdate(userId).size();
        if (activeSessionCount < maxDevices) {
            return;
        }
        authSessionRepository.findOldestActiveSessionForUserForUpdate(userId).ifPresent(session ->
            authSessionService.revokeSession(
                session.getSessionId(),
                AuthSessionStatus.KICKED,
                KICKED_BY_NEW_LOGIN_REASON
            )
        );
    }

    private TokenResponse issueToken(Long userId, String username, List<String> roleCodes, String sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("username", username);
        claims.put("roles", String.join(",", roleCodes));
        claims.put("jti", UUID.randomUUID().toString());
        if (sessionId != null && !sessionId.isBlank()) {
            claims.put("sid", sessionId);
        }
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

    public record LoginResult(TokenResponse tokenResponse, String refreshToken) {
    }

    public record RefreshResult(TokenResponse tokenResponse, String refreshToken) {
    }
}

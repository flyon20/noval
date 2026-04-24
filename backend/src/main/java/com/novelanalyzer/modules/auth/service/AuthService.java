package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import com.novelanalyzer.modules.auth.dto.PasswordResetRequest;
import com.novelanalyzer.modules.auth.dto.RegisterRequest;
import com.novelanalyzer.modules.auth.dto.SmsLoginRequest;
import com.novelanalyzer.modules.auth.model.AuthSessionStatus;
import com.novelanalyzer.modules.auth.model.AuthUserEntity;
import com.novelanalyzer.modules.auth.repository.AuthRepository;
import com.novelanalyzer.modules.auth.repository.AuthSessionRepository;
import com.novelanalyzer.modules.auth.vo.TokenResponse;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.auth.service.AuthSessionService.CreatedSession;
import com.novelanalyzer.modules.security.service.PasswordLoginRiskControlService;
import com.novelanalyzer.modules.security.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final String LOGIN_FAILED_MESSAGE = "登录失败，请检查手机号和密码";
    private static final String PHONE_NOT_FOUND_MESSAGE = "手机号未注册，请先注册";
    private static final String PASSWORD_INCORRECT_MESSAGE = "密码错误，请重新输入";
    private static final String USERNAME_EXISTS_MESSAGE = "用户名已存在，请更换后重试";
    private static final String ACCOUNT_DISABLED_MESSAGE = "账号不可用，请联系管理员";
    private static final String PASSWORD_RULE_MESSAGE = "密码需至少 8 位，且包含大写字母、小写字母和数字";
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT_PATTERN = Pattern.compile(".*\\d.*");
    private static final String KICKED_BY_NEW_LOGIN_REASON = "kicked by new login";
    private static final String ADMIN_ROLE_CODE = "ADMIN";
    private static final String USER_ROLE_CODE = "USER";
    private static final String BOOTSTRAP_ADMIN_PHONES_CONFIG_KEY = "auth.bootstrap-admin-phones";

    private final AuthProperties authProperties;
    private final AuthRepository authRepository;
    private final SystemConfigService systemConfigService;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final PasswordLoginRiskControlService passwordLoginRiskControlService;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuthSessionService authSessionService;
    private final AuthSessionRepository authSessionRepository;
    private final RefreshTokenService refreshTokenService;
    private final SmsAuthService smsAuthService;
    private final AtomicReference<String> cachedEncodedPassword = new AtomicReference<>();

    public AuthService(AuthProperties authProperties,
                       AuthRepository authRepository,
                       SystemConfigService systemConfigService,
                       JwtUtils jwtUtils,
                       PasswordEncoder passwordEncoder,
                       PasswordLoginRiskControlService passwordLoginRiskControlService,
                       TokenBlacklistService tokenBlacklistService,
                       AuthSessionService authSessionService,
                       AuthSessionRepository authSessionRepository,
                       RefreshTokenService refreshTokenService,
                       SmsAuthService smsAuthService) {
        this.authProperties = authProperties;
        this.authRepository = authRepository;
        this.systemConfigService = systemConfigService;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = passwordEncoder;
        this.passwordLoginRiskControlService = passwordLoginRiskControlService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.authSessionService = authSessionService;
        this.authSessionRepository = authSessionRepository;
        this.refreshTokenService = refreshTokenService;
        this.smsAuthService = smsAuthService;
    }

    @Transactional
    public LoginResult login(LoginRequest request, String loginIp) {
        String phone = normalizePhone(request.getPhone());
        AuthUserEntity dbUser = authRepository.findUserByPhone(phone).orElse(null);
        if (dbUser != null) {
            if (dbUser.getStatus() == null || dbUser.getStatus() != 1) {
                authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), dbUser.getPhone(), "PASSWORD", loginIp, 0, ACCOUNT_DISABLED_MESSAGE);
                passwordLoginRiskControlService.recordFailure(phone, loginIp);
                throw new BusinessException(ResultCode.UNAUTHORIZED, ACCOUNT_DISABLED_MESSAGE);
            }
            if (!passwordMatches(request.getPassword(), dbUser.getPassword())) {
                authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), dbUser.getPhone(), "PASSWORD", loginIp, 0, PASSWORD_INCORRECT_MESSAGE);
                passwordLoginRiskControlService.recordFailure(phone, loginIp);
                throw new BusinessException(ResultCode.UNAUTHORIZED, PASSWORD_INCORRECT_MESSAGE);
            }
            authRepository.lockUserById(dbUser.getId());
            ensureBootstrapAdminRole(dbUser);
            List<String> roleCodes = authRepository.findRoleCodesByUserId(dbUser.getId());
            passwordLoginRiskControlService.recordSuccess(phone, loginIp);
            authRepository.updateLastLoginTime(dbUser.getId());
            authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), dbUser.getPhone(), "PASSWORD", loginIp, 1, "login success");
            enforceDeviceLimit(dbUser.getId());
            CreatedSession session = authSessionService.createSession(dbUser.getId(), request.getDeviceLabel(), null, loginIp);
            String displayName = resolveDisplayName(dbUser);
            return new LoginResult(
                issueToken(dbUser.getId(), displayName, dbUser.getPhone(), roleCodes, session.sessionId()),
                session.refreshToken()
            );
        }

        if (authProperties.isDemoEnabled()) {
            validateDemoUser(phone, request.getPassword(), loginIp);
            passwordLoginRiskControlService.recordSuccess(phone, loginIp);
            CreatedSession session = authSessionService.createSession(0L, request.getDeviceLabel(), null, loginIp);
            return new LoginResult(issueToken(0L, phone, phone, List.of("ADMIN"), session.sessionId()), session.refreshToken());
        }

        authRepository.insertLoginLog(null, null, phone, "PASSWORD", loginIp, 0, PHONE_NOT_FOUND_MESSAGE);
        passwordLoginRiskControlService.recordFailure(phone, loginIp);
        throw new BusinessException(ResultCode.UNAUTHORIZED, PHONE_NOT_FOUND_MESSAGE);
    }

    @Transactional
    public LoginResult register(RegisterRequest request, String registerIp) {
        String phone = normalizePhone(request.getPhone());
        validatePasswordRule(request.getPassword());
        smsAuthService.verifyCode(phone, "REGISTER", request.getSmsCode(), request.getSmsOutId(), true);
        if (authRepository.existsUserByPhone(phone)) {
            authRepository.insertLoginLog(null, null, phone, "SMS", registerIp, 0, "手机号已注册，请直接登录");
            throw new BusinessException(ResultCode.BAD_REQUEST, "手机号已注册，请直接登录");
        }

        try {
            Long userId = authRepository.insertUser(phone, passwordEncoder.encode(request.getPassword()));
            Long defaultRoleId = authRepository.findActiveRoleIdByCode(USER_ROLE_CODE)
                .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "default role is not configured"));

            authRepository.insertUserRole(userId, defaultRoleId);
            ensureBootstrapAdminRole(userId, phone);
            List<String> roleCodes = authRepository.findRoleCodesByUserId(userId);
            authRepository.insertLoginLog(userId, null, phone, "SMS", registerIp, 1, "register success");
            CreatedSession session = authSessionService.createSession(userId, null, null, registerIp);
            return new LoginResult(
                issueToken(userId, phone, phone, roleCodes, session.sessionId()),
                session.refreshToken()
            );
        } catch (DuplicateKeyException ex) {
            authRepository.insertLoginLog(null, null, phone, "SMS", registerIp, 0, "手机号已注册，请直接登录");
            throw new BusinessException(ResultCode.BAD_REQUEST, "手机号已注册，请直接登录");
        }
    }

    @Transactional
    public LoginResult loginWithSms(SmsLoginRequest request, String loginIp) {
        String phone = normalizePhone(request.getPhone());
        AuthUserEntity dbUser = authRepository.findUserByPhone(phone)
            .orElseThrow(() -> new BusinessException(ResultCode.UNAUTHORIZED, PHONE_NOT_FOUND_MESSAGE));
        if (dbUser.getStatus() == null || dbUser.getStatus() != 1) {
            authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), dbUser.getPhone(), "SMS", loginIp, 0, ACCOUNT_DISABLED_MESSAGE);
            throw new BusinessException(ResultCode.UNAUTHORIZED, ACCOUNT_DISABLED_MESSAGE);
        }

        smsAuthService.verifyCode(phone, "LOGIN", request.getSmsCode(), request.getSmsOutId(), true);
        authRepository.lockUserById(dbUser.getId());
        ensureBootstrapAdminRole(dbUser);
        List<String> roleCodes = authRepository.findRoleCodesByUserId(dbUser.getId());
        authRepository.updateLastLoginTime(dbUser.getId());
        authRepository.insertLoginLog(dbUser.getId(), dbUser.getUsername(), dbUser.getPhone(), "SMS", loginIp, 1, "login success");
        enforceDeviceLimit(dbUser.getId());
        CreatedSession session = authSessionService.createSession(dbUser.getId(), request.getDeviceLabel(), null, loginIp);
        String displayName = resolveDisplayName(dbUser);
        return new LoginResult(
            issueToken(dbUser.getId(), displayName, dbUser.getPhone(), roleCodes, session.sessionId()),
            session.refreshToken()
        );
    }

    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String phone = normalizePhone(request.getPhone());
        AuthUserEntity dbUser = authRepository.findUserByPhone(phone)
            .orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST, PHONE_NOT_FOUND_MESSAGE));

        validatePasswordRule(request.getNewPassword());
        smsAuthService.verifyCode(phone, "RESET_PASSWORD", request.getSmsCode(), request.getSmsOutId(), true);
        authRepository.updatePasswordByPhone(phone, passwordEncoder.encode(request.getNewPassword()));
        authSessionRepository.revokeAllActiveSessionsByUserId(
            dbUser.getId(),
            AuthSessionStatus.REVOKED,
            "password reset",
            LocalDateTime.now()
        );
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
        AuthUserEntity dbUser = null;
        String username;
        List<String> roleCodes;
        if (session.getUserId() != null && session.getUserId() > 0) {
            dbUser = authRepository.findActiveUserById(session.getUserId())
                .orElseThrow(() -> new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired"));
            username = resolveDisplayName(dbUser);
            ensureBootstrapAdminRole(dbUser);
            roleCodes = authRepository.findRoleCodesByUserId(dbUser.getId());
        } else if (authProperties.isDemoEnabled()) {
            username = authProperties.getDemoUsername();
            roleCodes = List.of(ADMIN_ROLE_CODE);
        } else {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
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

        authSessionService.removeRefreshTokenMapping(refreshTokenHash);
        authSessionService.rehydrateSessionBySessionId(session.getSessionId());
        Long userId = dbUser != null ? dbUser.getId() : 0L;
        return new RefreshResult(issueToken(userId, username, dbUser == null ? null : dbUser.getPhone(), roleCodes, session.getSessionId()), nextRefreshToken);
    }

    public void logout(String accessToken, String refreshToken) {
        String sessionId = null;

        if (accessToken != null && !accessToken.isBlank()) {
            try {
                Claims claims = jwtUtils.parseClaims(accessToken, authProperties.getJwtSecret());
                sessionId = claims.get("sid", String.class);
                if (sessionId != null && !sessionId.isBlank()) {
                    long expireSeconds = Math.max(1L, (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000L);
                    tokenBlacklistService.blacklist(accessToken, expireSeconds);
                }
            } catch (JwtException ignored) {
                // Fall back to refresh-token-based logout when access token is expired or invalid.
            }
        }

        if ((sessionId == null || sessionId.isBlank()) && refreshToken != null && !refreshToken.isBlank()) {
            String refreshTokenHash = refreshTokenService.hashRefreshToken(refreshToken);
            sessionId = authSessionService.findActiveSessionByRefreshTokenHash(refreshTokenHash)
                .filter(session -> refreshTokenHash.equals(session.getRefreshTokenHash()))
                .map(session -> session.getSessionId())
                .orElse(null);
        }

        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }

        authSessionService.revokeSession(sessionId, AuthSessionStatus.REVOKED, "logout");
    }

    private void validateDemoUser(String phone, String password, String loginIp) {
        if (!authProperties.getDemoUsername().equals(phone)) {
            authRepository.insertLoginLog(null, authProperties.getDemoUsername(), phone, "PASSWORD", loginIp, 0, LOGIN_FAILED_MESSAGE);
            passwordLoginRiskControlService.recordFailure(phone, loginIp);
            throw new BusinessException(ResultCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE);
        }
        String encodedPassword = getOrInitEncodedPassword();
        if (!passwordEncoder.matches(password, encodedPassword)) {
            authRepository.insertLoginLog(null, authProperties.getDemoUsername(), phone, "PASSWORD", loginIp, 0, PASSWORD_INCORRECT_MESSAGE);
            passwordLoginRiskControlService.recordFailure(phone, loginIp);
            throw new BusinessException(ResultCode.UNAUTHORIZED, PASSWORD_INCORRECT_MESSAGE);
        }
        authRepository.insertLoginLog(0L, authProperties.getDemoUsername(), phone, "PASSWORD", loginIp, 1, "login success(demo)");
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

    private String normalizePhone(String phone) {
        return phone == null ? "" : phone.trim();
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

    private void ensureBootstrapAdminRole(AuthUserEntity user) {
        if (user == null) {
            return;
        }
        ensureBootstrapAdminRole(user.getId(), user.getPhone());
    }

    private void ensureBootstrapAdminRole(Long userId, String phone) {
        if (userId == null || userId <= 0 || !isBootstrapAdminPhone(phone)) {
            return;
        }
        Long adminRoleId = authRepository.findActiveRoleIdByCode(ADMIN_ROLE_CODE)
            .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "admin role is not configured"));
        authRepository.insertUserRoleIfMissing(userId, adminRoleId);
    }

    private boolean isBootstrapAdminPhone(String phone) {
        String configuredPhones = systemConfigService.getValueOrDefault(BOOTSTRAP_ADMIN_PHONES_CONFIG_KEY, "");
        String normalizedPhone = normalizePhone(phone);
        if (configuredPhones == null || configuredPhones.isBlank() || normalizedPhone.isBlank()) {
            return false;
        }
        return Arrays.stream(configuredPhones.split(","))
            .map(this::normalizePhone)
            .anyMatch(normalizedPhone::equals);
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

    private TokenResponse issueToken(Long userId,
                                     String username,
                                     String phone,
                                     List<String> roleCodes,
                                     String sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("username", username);
        claims.put("phone", phone);
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

    private TokenResponse issueToken(Long userId, String username, List<String> roleCodes, String sessionId) {
        return issueToken(userId, username, null, roleCodes, sessionId);
    }

    private String resolveDisplayName(AuthUserEntity user) {
        if (user == null) {
            return null;
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return user.getPhone();
    }

    public record LoginResult(TokenResponse tokenResponse, String refreshToken) {
    }

    public record RefreshResult(TokenResponse tokenResponse, String refreshToken) {
    }
}

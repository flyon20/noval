package com.novelanalyzer.modules.auth.controller;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.web.RequestIpResolver;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import com.novelanalyzer.modules.auth.dto.PasswordResetRequest;
import com.novelanalyzer.modules.auth.dto.RegisterRequest;
import com.novelanalyzer.modules.auth.dto.SmsLoginRequest;
import com.novelanalyzer.modules.auth.dto.SmsSendRequest;
import com.novelanalyzer.modules.auth.service.AuthService;
import com.novelanalyzer.modules.auth.service.SmsAuthService;
import com.novelanalyzer.modules.auth.service.TurnstileService;
import com.novelanalyzer.modules.auth.vo.SmsSendResponse;
import com.novelanalyzer.modules.auth.vo.TokenResponse;
import com.novelanalyzer.modules.security.repository.IpBlacklistRepository;
import com.novelanalyzer.modules.security.service.PasswordLoginRiskControlService;
import com.novelanalyzer.modules.security.service.RateLimitService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseCookie;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";

    private final AuthService authService;
    private final RateLimitService rateLimitService;
    private final RequestIpResolver requestIpResolver;
    private final IpBlacklistRepository ipBlacklistRepository;
    private final AuthProperties authProperties;
    private final SmsAuthService smsAuthService;
    private final TurnstileService turnstileService;
    private final PasswordLoginRiskControlService passwordLoginRiskControlService;

    public AuthController(AuthService authService,
                          RateLimitService rateLimitService,
                          RequestIpResolver requestIpResolver,
                          IpBlacklistRepository ipBlacklistRepository,
                          AuthProperties authProperties,
                          SmsAuthService smsAuthService,
                          TurnstileService turnstileService,
                          PasswordLoginRiskControlService passwordLoginRiskControlService) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
        this.requestIpResolver = requestIpResolver;
        this.ipBlacklistRepository = ipBlacklistRepository;
        this.authProperties = authProperties;
        this.smsAuthService = smsAuthService;
        this.turnstileService = turnstileService;
        this.passwordLoginRiskControlService = passwordLoginRiskControlService;
    }

    @PostMapping({"/login", "/login/password"})
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpServletRequest,
                                       HttpServletResponse httpServletResponse) {
        String requestIp = assertPublicAuthRequestAllowed(httpServletRequest, resolveLoginRateLimitPath(httpServletRequest));
        passwordLoginRiskControlService.assertAllowed(request.getPhone(), requestIp);
        AuthService.LoginResult loginResult = authService.login(request, requestIp);
        writeRefreshCookie(httpServletRequest, httpServletResponse, loginResult.refreshToken());
        return Result.success(loginResult.tokenResponse());
    }

    @PostMapping("/login/sms")
    public Result<TokenResponse> loginWithSms(@Valid @RequestBody SmsLoginRequest request,
                                              HttpServletRequest httpServletRequest,
                                              HttpServletResponse httpServletResponse) {
        String requestIp = assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/login/sms");
        AuthService.LoginResult loginResult = authService.loginWithSms(request, requestIp);
        writeRefreshCookie(httpServletRequest, httpServletResponse, loginResult.refreshToken());
        return Result.success(loginResult.tokenResponse());
    }

    @PostMapping("/sms/send")
    public Result<SmsSendResponse> sendSmsCode(@Valid @RequestBody SmsSendRequest request,
                                               HttpServletRequest httpServletRequest) {
        String requestIp = assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/sms/send");
        turnstileService.assertSmsSendPassed(request.getTurnstileToken(), requestIp);
        SmsAuthService.SendResult sendResult = smsAuthService.sendVerifyCode(request.getPhone(), request.getBizType(), requestIp);
        String debugVerifyCode = isLoopbackHost(requestIp) ? sendResult.debugVerifyCode() : null;
        SmsSendResponse response = new SmsSendResponse();
        response.setDebugVerifyCode(debugVerifyCode);
        response.setSmsOutId(sendResult.outId());
        return Result.success(response);
    }

    @PostMapping("/register")
    public Result<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletRequest httpServletRequest,
                                          HttpServletResponse httpServletResponse) {
        String requestIp = assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/register");
        AuthService.LoginResult registerResult = authService.register(request, requestIp);
        writeRefreshCookie(httpServletRequest, httpServletResponse, registerResult.refreshToken());
        return Result.success(registerResult.tokenResponse());
    }

    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request,
                                      HttpServletRequest httpServletRequest) {
        assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/password/reset");
        authService.resetPassword(request);
        return Result.success();
    }

    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(HttpServletRequest httpServletRequest,
                                         HttpServletResponse httpServletResponse) {
        assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/refresh");
        try {
            AuthService.RefreshResult refreshResult = authService.refresh(extractRefreshToken(httpServletRequest));
            writeRefreshCookie(httpServletRequest, httpServletResponse, refreshResult.refreshToken());
            return Result.success(refreshResult.tokenResponse());
        } catch (BusinessException ex) {
            if (ex.getResultCode() == ResultCode.UNAUTHORIZED || ex.getResultCode() == ResultCode.BAD_REQUEST) {
                clearRefreshCookie(httpServletRequest, httpServletResponse);
            }
            throw ex;
        }
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest httpServletRequest,
                               HttpServletResponse httpServletResponse) {
        assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/logout");
        try {
            authService.logout(
                extractBearerToken(httpServletRequest),
                extractOptionalRefreshToken(httpServletRequest)
            );
            clearRefreshCookie(httpServletRequest, httpServletResponse);
            return Result.success();
        } catch (BusinessException ex) {
            if (ex.getResultCode() == ResultCode.UNAUTHORIZED || ex.getResultCode() == ResultCode.BAD_REQUEST) {
                clearRefreshCookie(httpServletRequest, httpServletResponse);
            }
            throw ex;
        }
    }

    private String assertPublicAuthRequestAllowed(HttpServletRequest httpServletRequest, String path) {
        String requestIp = requestIpResolver.resolve(httpServletRequest);
        if (ipBlacklistRepository.isBlacklisted(requestIp)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "forbidden");
        }
        rateLimitService.assertWithinLimit(requestIp, path, null);
        return requestIp;
    }

    private String extractRefreshToken(HttpServletRequest request) {
        String refreshToken = extractOptionalRefreshToken(request);
        if (refreshToken != null) {
            return refreshToken;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "refresh token is required");
    }

    private String resolveLoginRateLimitPath(HttpServletRequest request) {
        if (request == null) {
            return "/api/auth/login/password";
        }
        String uri = request.getRequestURI();
        if ("/api/auth/login".equals(uri) || "/api/auth/login/password".equals(uri)) {
            return "/api/auth/login/password";
        }
        return uri;
    }

    private String extractOptionalRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (authProperties.getRefreshCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
                return null;
            }
        }
        return null;
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || authorization.isBlank() || !authorization.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        String token = authorization.substring(TOKEN_PREFIX.length()).trim();
        return token.isBlank() ? null : token;
    }

    private void writeRefreshCookie(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(authProperties.getRefreshCookieName(), refreshToken)
            .httpOnly(true)
            .secure(shouldUseSecureCookie(request))
            .path(authProperties.getRefreshCookiePath())
            .sameSite(authProperties.getRefreshCookieSameSite())
            .maxAge(Duration.ofSeconds(authProperties.getRefreshTokenExpireSeconds()))
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearRefreshCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(authProperties.getRefreshCookieName(), "")
            .httpOnly(true)
            .secure(shouldUseSecureCookie(request))
            .path(authProperties.getRefreshCookiePath())
            .sameSite(authProperties.getRefreshCookieSameSite())
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private boolean shouldUseSecureCookie(HttpServletRequest request) {
        if (!authProperties.isRefreshCookieSecure()) {
            return false;
        }
        if (request == null) {
            return true;
        }
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && forwardedProto.toLowerCase().contains("https")) {
            return true;
        }
        return !isLoopbackHttpUrl(request.getHeader("Origin")) && !isLoopbackHttpUrl(request.getHeader("Referer"));
    }

    private boolean isLoopbackHttpUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(candidate);
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(host);
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host)
            || "[::1]".equals(host);
    }
}

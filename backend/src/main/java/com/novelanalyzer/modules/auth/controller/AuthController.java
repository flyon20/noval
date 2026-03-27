package com.novelanalyzer.modules.auth.controller;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.web.RequestIpResolver;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import com.novelanalyzer.modules.auth.dto.RefreshTokenRequest;
import com.novelanalyzer.modules.auth.dto.RegisterRequest;
import com.novelanalyzer.modules.auth.service.AuthService;
import com.novelanalyzer.modules.auth.vo.TokenResponse;
import com.novelanalyzer.modules.security.repository.IpBlacklistRepository;
import com.novelanalyzer.modules.security.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseCookie;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public AuthController(AuthService authService,
                          RateLimitService rateLimitService,
                          RequestIpResolver requestIpResolver,
                          IpBlacklistRepository ipBlacklistRepository,
                          AuthProperties authProperties) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
        this.requestIpResolver = requestIpResolver;
        this.ipBlacklistRepository = ipBlacklistRepository;
        this.authProperties = authProperties;
    }

    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpServletRequest,
                                       HttpServletResponse httpServletResponse) {
        String requestIp = assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/login");
        AuthService.LoginResult loginResult = authService.login(request, requestIp);
        writeRefreshCookie(httpServletResponse, loginResult.refreshToken());
        return Result.success(loginResult.tokenResponse());
    }

    @PostMapping("/register")
    public Result<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletRequest httpServletRequest) {
        String requestIp = assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/register");
        return Result.success(authService.register(request, requestIp));
    }

    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@RequestBody(required = false) RefreshTokenRequest request,
                                         HttpServletRequest httpServletRequest) {
        assertPublicAuthRequestAllowed(httpServletRequest, "/api/auth/refresh");
        return Result.success(authService.refresh(resolveToken(httpServletRequest, request)));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) RefreshTokenRequest request,
                               HttpServletRequest httpServletRequest) {
        String bearerToken = extractBearerToken(httpServletRequest);
        if (request != null && request.getToken() != null && !request.getToken().isBlank() && !request.getToken().equals(bearerToken)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "token mismatch");
        }
        authService.logout(resolveToken(httpServletRequest, request));
        return Result.success();
    }

    private String assertPublicAuthRequestAllowed(HttpServletRequest httpServletRequest, String path) {
        String requestIp = requestIpResolver.resolve(httpServletRequest);
        if (ipBlacklistRepository.isBlacklisted(requestIp)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "forbidden");
        }
        rateLimitService.assertWithinLimit(requestIp, path, null);
        return requestIp;
    }

    private String resolveToken(HttpServletRequest request, RefreshTokenRequest body) {
        String bearerToken = extractBearerToken(request);
        if (bearerToken != null && body != null && body.getToken() != null && !body.getToken().isBlank()
            && !body.getToken().trim().equals(bearerToken)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "token mismatch");
        }
        if (bearerToken != null) {
            return bearerToken;
        }
        if (body != null && body.getToken() != null && !body.getToken().isBlank()) {
            return body.getToken().trim();
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "token is required");
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || authorization.isBlank() || !authorization.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        String token = authorization.substring(TOKEN_PREFIX.length()).trim();
        return token.isBlank() ? null : token;
    }

    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(authProperties.getRefreshCookieName(), refreshToken)
            .httpOnly(true)
            .secure(authProperties.isRefreshCookieSecure())
            .path(authProperties.getRefreshCookiePath())
            .sameSite(authProperties.getRefreshCookieSameSite())
            .maxAge(Duration.ofSeconds(authProperties.getRefreshTokenExpireSeconds()))
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}

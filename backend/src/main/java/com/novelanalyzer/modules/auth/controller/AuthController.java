package com.novelanalyzer.modules.auth.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.web.RequestIpResolver;
import com.novelanalyzer.modules.auth.dto.LoginRequest;
import com.novelanalyzer.modules.auth.dto.RefreshTokenRequest;
import com.novelanalyzer.modules.auth.service.AuthService;
import com.novelanalyzer.modules.auth.vo.TokenResponse;
import com.novelanalyzer.modules.security.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimitService;
    private final RequestIpResolver requestIpResolver;

    public AuthController(AuthService authService,
                          RateLimitService rateLimitService,
                          RequestIpResolver requestIpResolver) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
        this.requestIpResolver = requestIpResolver;
    }

    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpServletRequest) {
        String requestIp = requestIpResolver.resolve(httpServletRequest);
        rateLimitService.assertWithinLimit(requestIp, "/api/auth/login", null);
        return Result.success(authService.login(request, requestIp));
    }

    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.success(authService.refresh(request.getToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getToken());
        return Result.success();
    }
}

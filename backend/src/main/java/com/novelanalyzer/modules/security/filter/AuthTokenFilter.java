package com.novelanalyzer.modules.security.filter;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.web.RequestIpResolver;
import com.novelanalyzer.common.utils.JsonResponseWriter;
import com.novelanalyzer.common.utils.JwtUtils;
import com.novelanalyzer.config.AuthProperties;
import com.novelanalyzer.config.SecurityProperties;
import com.novelanalyzer.modules.auth.model.AuthSessionEntity;
import com.novelanalyzer.modules.auth.service.AuthSessionService;
import com.novelanalyzer.modules.security.repository.IpBlacklistRepository;
import com.novelanalyzer.modules.security.repository.OperationLogRepository;
import com.novelanalyzer.modules.security.service.RateLimitService;
import com.novelanalyzer.modules.security.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthTokenFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final AuthProperties authProperties;
    private final SecurityProperties securityProperties;
    private final TokenBlacklistService tokenBlacklistService;
    private final RateLimitService rateLimitService;
    private final IpBlacklistRepository ipBlacklistRepository;
    private final OperationLogRepository operationLogRepository;
    private final JsonResponseWriter jsonResponseWriter;
    private final RequestIpResolver requestIpResolver;
    private final AuthSessionService authSessionService;

    public AuthTokenFilter(JwtUtils jwtUtils,
                           AuthProperties authProperties,
                           SecurityProperties securityProperties,
                           TokenBlacklistService tokenBlacklistService,
                           RateLimitService rateLimitService,
                           IpBlacklistRepository ipBlacklistRepository,
                           OperationLogRepository operationLogRepository,
                           JsonResponseWriter jsonResponseWriter,
                           RequestIpResolver requestIpResolver,
                           AuthSessionService authSessionService) {
        this.jwtUtils = jwtUtils;
        this.authProperties = authProperties;
        this.securityProperties = securityProperties;
        this.tokenBlacklistService = tokenBlacklistService;
        this.rateLimitService = rateLimitService;
        this.ipBlacklistRepository = ipBlacklistRepository;
        this.operationLogRepository = operationLogRepository;
        this.jsonResponseWriter = jsonResponseWriter;
        this.requestIpResolver = requestIpResolver;
        this.authSessionService = authSessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestPath = request.getRequestURI();
        if (isWhitelisted(requestPath) || !isProtectedPath(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestIp = requestIpResolver.resolve(request);
        if (ipBlacklistRepository.isBlacklisted(requestIp)) {
            operationLogRepository.insertOperationLog(
                null,
                "anonymous",
                "SECURITY",
                "IP_BLACKLIST",
                "blocked by ip blacklist",
                0,
                "ip is blacklisted",
                requestIp
            );
            jsonResponseWriter.write(response, Result.fail(ResultCode.FORBIDDEN, "forbidden"));
            return;
        }

        String token = extractToken(request);
        if (token == null) {
            operationLogRepository.insertOperationLog(
                null,
                "anonymous",
                "SECURITY",
                "AUTH",
                "missing token",
                0,
                "missing token",
                requestIp
            );
            jsonResponseWriter.write(response, Result.fail(ResultCode.UNAUTHORIZED, "unauthorized"));
            return;
        }

        if (tokenBlacklistService.isBlacklisted(token)) {
            jsonResponseWriter.write(response, Result.fail(ResultCode.UNAUTHORIZED, "token is invalid or expired"));
            return;
        }

        try {
            Claims claims = jwtUtils.parseClaims(token, authProperties.getJwtSecret());
            validateSession(claims);
            AuthUser authUser = buildAuthUser(claims);
            AuthUserHolder.set(authUser);
            rateLimitService.assertWithinLimit(requestIp, requestPath, authUser);
            filterChain.doFilter(request, response);
        } catch (JwtException ex) {
            jsonResponseWriter.write(response, Result.fail(ResultCode.UNAUTHORIZED, "token is invalid or expired"));
        } catch (Exception ex) {
            if (ex instanceof BusinessException be) {
                jsonResponseWriter.write(response, Result.fail(be.getResultCode(), be.getMessage()));
                return;
            }
            throw ex;
        } finally {
            AuthUserHolder.clear();
        }
    }

    private boolean isWhitelisted(String requestPath) {
        return securityProperties.getWhitelistPaths().stream().anyMatch(requestPath::startsWith);
    }

    private boolean isProtectedPath(String requestPath) {
        return securityProperties.getProtectedPathPrefixes().stream().anyMatch(requestPath::startsWith);
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader == null || authHeader.isBlank() || !authHeader.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        return authHeader.substring(TOKEN_PREFIX.length()).trim();
    }

    private AuthUser buildAuthUser(Claims claims) {
        Long userId = claims.get("uid", Long.class);
        if (userId == null) {
            userId = 0L;
        }
        String username = claims.get("username", String.class);
        if (username == null || username.isBlank()) {
            username = claims.getSubject();
        }
        String rolesValue = claims.get("roles", String.class);
        Set<String> roles = new HashSet<>();
        if (rolesValue != null && !rolesValue.isBlank()) {
            roles.addAll(Arrays.asList(rolesValue.split(",")));
        }
        return AuthUser.of(userId, username, roles);
    }

    private void validateSession(Claims claims) {
        Long userId = claims.get("uid", Long.class);
        String sessionId = claims.get("sid", String.class);
        if (userId == null || userId <= 0 || sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
        AuthSessionEntity session = authSessionService.findActiveSessionBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "token is invalid or expired");
        }
        authSessionService.updateActivity(sessionId);
    }
}

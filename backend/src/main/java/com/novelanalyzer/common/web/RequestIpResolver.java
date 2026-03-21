package com.novelanalyzer.common.web;

import com.novelanalyzer.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RequestIpResolver {

    private final SecurityProperties securityProperties;

    public RequestIpResolver(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = normalize(request.getRemoteAddr());
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }

        String[] parts = forwarded.split(",");
        if (parts.length == 0) {
            return remoteAddr;
        }

        String clientIp = normalize(parts[0]);
        return clientIp.isBlank() ? remoteAddr : clientIp;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return securityProperties.getTrustedProxyIps().stream()
            .filter(this::hasText)
            .map(String::trim)
            .anyMatch(remoteAddr::equals);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

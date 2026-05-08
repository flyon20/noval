package com.novelanalyzer.common.web;

import com.novelanalyzer.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

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

        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (!realIp.isBlank()) {
            return realIp;
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
            .anyMatch(candidate -> matchesTrustedProxy(remoteAddr, candidate));
    }

    private boolean matchesTrustedProxy(String remoteAddr, String candidate) {
        if (candidate.contains("/")) {
            return matchesCidr(remoteAddr, candidate);
        }
        return remoteAddr.equals(candidate);
    }

    private boolean matchesCidr(String remoteAddr, String cidr) {
        try {
            String[] parts = cidr.split("/", 2);
            if (parts.length != 2) {
                return false;
            }
            InetAddress remote = InetAddress.getByName(remoteAddr);
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] remoteBytes = remote.getAddress();
            byte[] networkBytes = network.getAddress();
            if (remoteBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (remoteBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            if (remainingBits == 0) {
                return true;
            }

            int mask = (-1) << (8 - remainingBits);
            return (remoteBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException ex) {
            return false;
        }
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

package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private int rateLimitPerMinute = 100;
    private int autoBlacklistThreshold = 20;
    private long blacklistSeconds = 86400L;
    private List<String> protectedPathPrefixes = new ArrayList<>(Arrays.asList(
        "/api/auth",
        "/api/secure",
        "/api/system",
        "/api/crawler",
        "/api/analysis",
        "/api/config",
        "/api/data"
    ));
    private List<String> whitelistPaths = new ArrayList<>(Arrays.asList(
        "/api/system/health",
        "/api/auth/login",
        "/api/auth/refresh"
    ));
    private List<String> trustedProxyIps = new ArrayList<>();

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public int getAutoBlacklistThreshold() {
        return autoBlacklistThreshold;
    }

    public void setAutoBlacklistThreshold(int autoBlacklistThreshold) {
        this.autoBlacklistThreshold = autoBlacklistThreshold;
    }

    public long getBlacklistSeconds() {
        return blacklistSeconds;
    }

    public void setBlacklistSeconds(long blacklistSeconds) {
        this.blacklistSeconds = blacklistSeconds;
    }

    public List<String> getProtectedPathPrefixes() {
        return protectedPathPrefixes;
    }

    public void setProtectedPathPrefixes(List<String> protectedPathPrefixes) {
        this.protectedPathPrefixes = protectedPathPrefixes;
    }

    public List<String> getWhitelistPaths() {
        return whitelistPaths;
    }

    public void setWhitelistPaths(List<String> whitelistPaths) {
        this.whitelistPaths = whitelistPaths;
    }

    public List<String> getTrustedProxyIps() {
        return trustedProxyIps;
    }

    public void setTrustedProxyIps(List<String> trustedProxyIps) {
        this.trustedProxyIps = trustedProxyIps;
    }
}

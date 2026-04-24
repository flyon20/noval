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
    private long passwordLoginPhoneWindowSeconds = 600L;
    private int passwordLoginPhoneMaxFailures = 6;
    private long passwordLoginIpWindowSeconds = 600L;
    private int passwordLoginIpMaxFailures = 20;
    private long passwordLoginPhoneIpWindowSeconds = 600L;
    private int passwordLoginPhoneIpMaxFailures = 4;
    private long passwordLoginCooldownSeconds = 900L;
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
        "/api/system/auth-public-config",
        "/api/auth/login",
        "/api/auth/register",
        "/api/auth/refresh",
        "/api/auth/logout"
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

    public long getPasswordLoginPhoneWindowSeconds() {
        return passwordLoginPhoneWindowSeconds;
    }

    public void setPasswordLoginPhoneWindowSeconds(long passwordLoginPhoneWindowSeconds) {
        this.passwordLoginPhoneWindowSeconds = passwordLoginPhoneWindowSeconds;
    }

    public int getPasswordLoginPhoneMaxFailures() {
        return passwordLoginPhoneMaxFailures;
    }

    public void setPasswordLoginPhoneMaxFailures(int passwordLoginPhoneMaxFailures) {
        this.passwordLoginPhoneMaxFailures = passwordLoginPhoneMaxFailures;
    }

    public long getPasswordLoginIpWindowSeconds() {
        return passwordLoginIpWindowSeconds;
    }

    public void setPasswordLoginIpWindowSeconds(long passwordLoginIpWindowSeconds) {
        this.passwordLoginIpWindowSeconds = passwordLoginIpWindowSeconds;
    }

    public int getPasswordLoginIpMaxFailures() {
        return passwordLoginIpMaxFailures;
    }

    public void setPasswordLoginIpMaxFailures(int passwordLoginIpMaxFailures) {
        this.passwordLoginIpMaxFailures = passwordLoginIpMaxFailures;
    }

    public long getPasswordLoginPhoneIpWindowSeconds() {
        return passwordLoginPhoneIpWindowSeconds;
    }

    public void setPasswordLoginPhoneIpWindowSeconds(long passwordLoginPhoneIpWindowSeconds) {
        this.passwordLoginPhoneIpWindowSeconds = passwordLoginPhoneIpWindowSeconds;
    }

    public int getPasswordLoginPhoneIpMaxFailures() {
        return passwordLoginPhoneIpMaxFailures;
    }

    public void setPasswordLoginPhoneIpMaxFailures(int passwordLoginPhoneIpMaxFailures) {
        this.passwordLoginPhoneIpMaxFailures = passwordLoginPhoneIpMaxFailures;
    }

    public long getPasswordLoginCooldownSeconds() {
        return passwordLoginCooldownSeconds;
    }

    public void setPasswordLoginCooldownSeconds(long passwordLoginCooldownSeconds) {
        this.passwordLoginCooldownSeconds = passwordLoginCooldownSeconds;
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

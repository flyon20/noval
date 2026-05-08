package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cloudflare.turnstile")
public class CloudflareTurnstileProperties {

    private boolean enabled;
    private String siteKey;
    private String secretKey;
    private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private String expectedHostname;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getVerifyUrl() {
        return verifyUrl;
    }

    public void setVerifyUrl(String verifyUrl) {
        this.verifyUrl = verifyUrl;
    }

    public String getExpectedHostname() {
        return expectedHostname;
    }

    public void setExpectedHostname(String expectedHostname) {
        this.expectedHostname = expectedHostname;
    }
}

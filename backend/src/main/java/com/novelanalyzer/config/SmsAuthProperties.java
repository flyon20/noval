package com.novelanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sms-auth")
public class SmsAuthProperties {

    private String accessKeyId;
    private String accessKeySecret;
    private String endpoint = "dypnsapi.aliyuncs.com";
    private String countryCode = "86";
    private String schemeName = "noval-web";
    private String signName;
    private String templateCodeRegister;
    private String templateCodeLogin;
    private String templateCodeResetPassword;
    private long validTimeMinutes = 5L;
    private long intervalSeconds = 60L;
    private long phoneWindowSeconds = 600L;
    private int phoneMaxAttempts = 3;
    private long ipWindowSeconds = 600L;
    private int ipMaxAttempts = 5;
    private long bizWindowSeconds = 1800L;
    private int bizMaxAttempts = 3;
    private boolean enabled;

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public String getSchemeName() {
        return schemeName;
    }

    public void setSchemeName(String schemeName) {
        this.schemeName = schemeName;
    }

    public String getSignName() {
        return signName;
    }

    public void setSignName(String signName) {
        this.signName = signName;
    }

    public String getTemplateCodeRegister() {
        return templateCodeRegister;
    }

    public void setTemplateCodeRegister(String templateCodeRegister) {
        this.templateCodeRegister = templateCodeRegister;
    }

    public String getTemplateCodeLogin() {
        return templateCodeLogin;
    }

    public void setTemplateCodeLogin(String templateCodeLogin) {
        this.templateCodeLogin = templateCodeLogin;
    }

    public String getTemplateCodeResetPassword() {
        return templateCodeResetPassword;
    }

    public void setTemplateCodeResetPassword(String templateCodeResetPassword) {
        this.templateCodeResetPassword = templateCodeResetPassword;
    }

    public long getValidTimeMinutes() {
        return validTimeMinutes;
    }

    public void setValidTimeMinutes(long validTimeMinutes) {
        this.validTimeMinutes = validTimeMinutes;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public long getPhoneWindowSeconds() {
        return phoneWindowSeconds;
    }

    public void setPhoneWindowSeconds(long phoneWindowSeconds) {
        this.phoneWindowSeconds = phoneWindowSeconds;
    }

    public int getPhoneMaxAttempts() {
        return phoneMaxAttempts;
    }

    public void setPhoneMaxAttempts(int phoneMaxAttempts) {
        this.phoneMaxAttempts = phoneMaxAttempts;
    }

    public long getIpWindowSeconds() {
        return ipWindowSeconds;
    }

    public void setIpWindowSeconds(long ipWindowSeconds) {
        this.ipWindowSeconds = ipWindowSeconds;
    }

    public int getIpMaxAttempts() {
        return ipMaxAttempts;
    }

    public void setIpMaxAttempts(int ipMaxAttempts) {
        this.ipMaxAttempts = ipMaxAttempts;
    }

    public long getBizWindowSeconds() {
        return bizWindowSeconds;
    }

    public void setBizWindowSeconds(long bizWindowSeconds) {
        this.bizWindowSeconds = bizWindowSeconds;
    }

    public int getBizMaxAttempts() {
        return bizMaxAttempts;
    }

    public void setBizMaxAttempts(int bizMaxAttempts) {
        this.bizMaxAttempts = bizMaxAttempts;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

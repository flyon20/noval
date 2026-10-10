package com.novelanalyzer.modules.knowledge.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentProviderProbeVO {

    private String status;
    private String profileKey;
    private String profileVersion;
    private String endpointFingerprint;
    private String model;
    private String protocol;
    private Long latencyMillis;
    private Boolean usageReported;
    private Boolean cacheUsageReported;
    private String errorCode;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProfileKey() { return profileKey; }
    public void setProfileKey(String profileKey) { this.profileKey = profileKey; }
    public String getProfileVersion() { return profileVersion; }
    public void setProfileVersion(String profileVersion) { this.profileVersion = profileVersion; }
    public String getEndpointFingerprint() { return endpointFingerprint; }
    public void setEndpointFingerprint(String endpointFingerprint) { this.endpointFingerprint = endpointFingerprint; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public Long getLatencyMillis() { return latencyMillis; }
    public void setLatencyMillis(Long latencyMillis) { this.latencyMillis = latencyMillis; }
    public Boolean getUsageReported() { return usageReported; }
    public void setUsageReported(Boolean usageReported) { this.usageReported = usageReported; }
    public Boolean getCacheUsageReported() { return cacheUsageReported; }
    public void setCacheUsageReported(Boolean cacheUsageReported) { this.cacheUsageReported = cacheUsageReported; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
}

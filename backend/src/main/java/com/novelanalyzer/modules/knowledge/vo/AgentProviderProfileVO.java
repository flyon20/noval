package com.novelanalyzer.modules.knowledge.vo;

import com.novelanalyzer.modules.config.model.AiProviderCapabilities;

public class AgentProviderProfileVO {

    private String profileKey;
    private String profileVersion;
    private String endpoint;
    private String model;
    private String providerType;
    private String protocol;
    private AiProviderCapabilities providerCapabilities;
    private Boolean enabled;
    private Boolean isDefault;
    private Boolean apiKeyConfigured;

    public String getProfileKey() { return profileKey; }
    public void setProfileKey(String profileKey) { this.profileKey = profileKey; }
    public String getProfileVersion() { return profileVersion; }
    public void setProfileVersion(String profileVersion) { this.profileVersion = profileVersion; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public AiProviderCapabilities getProviderCapabilities() { return providerCapabilities; }
    public void setProviderCapabilities(AiProviderCapabilities providerCapabilities) {
        this.providerCapabilities = providerCapabilities;
    }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public Boolean getApiKeyConfigured() { return apiKeyConfigured; }
    public void setApiKeyConfigured(Boolean apiKeyConfigured) { this.apiKeyConfigured = apiKeyConfigured; }
}

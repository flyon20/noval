package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AgentProviderRuntimeResolveRequest {

    @NotBlank
    @Size(max = 255)
    private String profileKey;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String profileVersion;

    public String getProfileKey() {
        return profileKey;
    }

    public void setProfileKey(String profileKey) {
        this.profileKey = profileKey;
    }

    public String getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(String profileVersion) {
        this.profileVersion = profileVersion;
    }
}

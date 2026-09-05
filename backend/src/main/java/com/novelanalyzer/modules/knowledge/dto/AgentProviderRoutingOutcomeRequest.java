package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AgentProviderRoutingOutcomeRequest {

    @NotBlank
    @Size(max = 255)
    private String profileKey;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String profileVersion;

    @NotBlank
    @Pattern(regexp = "SUCCEEDED|TRANSIENT_FAILURE")
    private String outcome;

    @Pattern(regexp = "CONNECT_ERROR|TIMEOUT|HTTP_429|HTTP_500|HTTP_503")
    private String failureClass;

    @NotNull
    private Boolean switched;

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

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public void setFailureClass(String failureClass) {
        this.failureClass = failureClass;
    }

    public Boolean getSwitched() {
        return switched;
    }

    public void setSwitched(Boolean switched) {
        this.switched = switched;
    }
}

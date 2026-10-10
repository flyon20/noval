package com.novelanalyzer.modules.config.vo;

import java.util.ArrayList;
import java.util.List;

public class ProviderTierVO {
    private String modelKey;
    private String family;
    private Boolean supportsReasoning;
    private List<String> reasoningTiers = new ArrayList<>();
    private Boolean acceptsTemperature;

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public Boolean getSupportsReasoning() {
        return supportsReasoning;
    }

    public void setSupportsReasoning(Boolean supportsReasoning) {
        this.supportsReasoning = supportsReasoning;
    }

    public List<String> getReasoningTiers() {
        return reasoningTiers;
    }

    public void setReasoningTiers(List<String> reasoningTiers) {
        this.reasoningTiers = reasoningTiers == null ? new ArrayList<>() : reasoningTiers;
    }

    public Boolean getAcceptsTemperature() {
        return acceptsTemperature;
    }

    public void setAcceptsTemperature(Boolean acceptsTemperature) {
        this.acceptsTemperature = acceptsTemperature;
    }
}

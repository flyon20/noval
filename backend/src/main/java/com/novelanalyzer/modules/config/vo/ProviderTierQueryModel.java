package com.novelanalyzer.modules.config.vo;

public class ProviderTierQueryModel {
    private String modelKey;
    private String providerType;
    private String modelName;

    public ProviderTierQueryModel() {
    }

    public ProviderTierQueryModel(String modelKey, String providerType, String modelName) {
        this.modelKey = modelKey;
        this.providerType = providerType;
        this.modelName = modelName;
    }

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
}

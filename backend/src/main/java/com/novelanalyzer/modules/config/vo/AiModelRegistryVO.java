package com.novelanalyzer.modules.config.vo;

import com.novelanalyzer.modules.config.model.AiProviderRoutingPolicy;

import java.util.ArrayList;
import java.util.List;

public class AiModelRegistryVO {

    private String defaultModelKey;
    private AiProviderRoutingPolicy providerRoutingPolicy = new AiProviderRoutingPolicy();
    private List<AiModelRegistryModelVO> models = new ArrayList<>();

    public String getDefaultModelKey() {
        return defaultModelKey;
    }

    public void setDefaultModelKey(String defaultModelKey) {
        this.defaultModelKey = defaultModelKey;
    }

    public AiProviderRoutingPolicy getProviderRoutingPolicy() {
        return providerRoutingPolicy;
    }

    public void setProviderRoutingPolicy(AiProviderRoutingPolicy providerRoutingPolicy) {
        this.providerRoutingPolicy = providerRoutingPolicy;
    }

    public List<AiModelRegistryModelVO> getModels() {
        return models;
    }

    public void setModels(List<AiModelRegistryModelVO> models) {
        this.models = models;
    }
}

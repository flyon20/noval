package com.novelanalyzer.modules.config.vo;

import java.util.ArrayList;
import java.util.List;

public class AiModelRegistryVO {

    private String defaultModelKey;
    private List<AiModelRegistryModelVO> models = new ArrayList<>();

    public String getDefaultModelKey() {
        return defaultModelKey;
    }

    public void setDefaultModelKey(String defaultModelKey) {
        this.defaultModelKey = defaultModelKey;
    }

    public List<AiModelRegistryModelVO> getModels() {
        return models;
    }

    public void setModels(List<AiModelRegistryModelVO> models) {
        this.models = models;
    }
}

package com.novelanalyzer.modules.analysis.model;

import java.util.Map;

public class AiInvokeResult {

    private String modelName;
    private String content;
    private int tokenUsed;
    private Map<String, Object> resultJson;

    public static AiInvokeResult of(String modelName, String content, int tokenUsed) {
        return of(modelName, content, tokenUsed, Map.of());
    }

    public static AiInvokeResult of(String modelName, String content, int tokenUsed, Map<String, Object> resultJson) {
        AiInvokeResult result = new AiInvokeResult();
        result.setModelName(modelName);
        result.setContent(content);
        result.setTokenUsed(tokenUsed);
        result.setResultJson(resultJson);
        return result;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getTokenUsed() {
        return tokenUsed;
    }

    public void setTokenUsed(int tokenUsed) {
        this.tokenUsed = tokenUsed;
    }

    public Map<String, Object> getResultJson() {
        return resultJson;
    }

    public void setResultJson(Map<String, Object> resultJson) {
        this.resultJson = resultJson;
    }
}

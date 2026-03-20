package com.novelanalyzer.modules.analysis.model;

public class AiInvokeResult {

    private String modelName;
    private String content;
    private int tokenUsed;

    public static AiInvokeResult of(String modelName, String content, int tokenUsed) {
        AiInvokeResult result = new AiInvokeResult();
        result.setModelName(modelName);
        result.setContent(content);
        result.setTokenUsed(tokenUsed);
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
}


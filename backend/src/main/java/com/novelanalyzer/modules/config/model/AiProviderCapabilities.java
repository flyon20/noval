package com.novelanalyzer.modules.config.model;

public class AiProviderCapabilities {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private Integer schemaVersion;
    private Boolean supportsStreaming;
    private Boolean supportsTools;
    private Boolean supportsJsonObject;
    private Boolean supportsReasoning;
    private Boolean reportsUsage;
    private Boolean reportsCacheUsage;
    private AiPromptCacheCapabilities promptCache;

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Boolean getSupportsStreaming() {
        return supportsStreaming;
    }

    public void setSupportsStreaming(Boolean supportsStreaming) {
        this.supportsStreaming = supportsStreaming;
    }

    public Boolean getSupportsTools() {
        return supportsTools;
    }

    public void setSupportsTools(Boolean supportsTools) {
        this.supportsTools = supportsTools;
    }

    public Boolean getSupportsJsonObject() {
        return supportsJsonObject;
    }

    public void setSupportsJsonObject(Boolean supportsJsonObject) {
        this.supportsJsonObject = supportsJsonObject;
    }

    public Boolean getSupportsReasoning() {
        return supportsReasoning;
    }

    public void setSupportsReasoning(Boolean supportsReasoning) {
        this.supportsReasoning = supportsReasoning;
    }

    public Boolean getReportsUsage() {
        return reportsUsage;
    }

    public void setReportsUsage(Boolean reportsUsage) {
        this.reportsUsage = reportsUsage;
    }

    public Boolean getReportsCacheUsage() {
        return reportsCacheUsage;
    }

    public void setReportsCacheUsage(Boolean reportsCacheUsage) {
        this.reportsCacheUsage = reportsCacheUsage;
    }

    public AiPromptCacheCapabilities getPromptCache() {
        return promptCache;
    }

    public void setPromptCache(AiPromptCacheCapabilities promptCache) {
        this.promptCache = promptCache;
    }

    public AiProviderCapabilities copy() {
        AiProviderCapabilities copy = new AiProviderCapabilities();
        copy.setSchemaVersion(schemaVersion);
        copy.setSupportsStreaming(supportsStreaming);
        copy.setSupportsTools(supportsTools);
        copy.setSupportsJsonObject(supportsJsonObject);
        copy.setSupportsReasoning(supportsReasoning);
        copy.setReportsUsage(reportsUsage);
        copy.setReportsCacheUsage(reportsCacheUsage);
        copy.setPromptCache(promptCache == null ? null : promptCache.copy());
        return copy;
    }
}

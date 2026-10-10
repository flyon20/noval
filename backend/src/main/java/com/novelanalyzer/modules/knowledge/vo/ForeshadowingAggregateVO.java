package com.novelanalyzer.modules.knowledge.vo;

import java.util.LinkedHashMap;
import java.util.Map;

public class ForeshadowingAggregateVO {
    private Long userId;
    private Long projectId;
    private Long workId;
    private String metric;
    private long count;
    private Map<String, Long> breakdown = new LinkedHashMap<>();
    private boolean complete;
    private boolean partial;
    private boolean recognizedRecordsOnly;
    private String generationFingerprint;
    private long activeChapterGenerationCount;
    private long activeDocumentGenerationCount;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getWorkId() { return workId; }
    public void setWorkId(Long workId) { this.workId = workId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
    public Map<String, Long> getBreakdown() { return breakdown; }
    public void setBreakdown(Map<String, Long> breakdown) {
        this.breakdown = breakdown == null ? new LinkedHashMap<>() : new LinkedHashMap<>(breakdown);
    }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }
    public boolean isPartial() { return partial; }
    public void setPartial(boolean partial) { this.partial = partial; }
    public boolean isRecognizedRecordsOnly() { return recognizedRecordsOnly; }
    public void setRecognizedRecordsOnly(boolean recognizedRecordsOnly) { this.recognizedRecordsOnly = recognizedRecordsOnly; }
    public String getGenerationFingerprint() { return generationFingerprint; }
    public void setGenerationFingerprint(String generationFingerprint) { this.generationFingerprint = generationFingerprint; }
    public long getActiveChapterGenerationCount() { return activeChapterGenerationCount; }
    public void setActiveChapterGenerationCount(long activeChapterGenerationCount) { this.activeChapterGenerationCount = activeChapterGenerationCount; }
    public long getActiveDocumentGenerationCount() { return activeDocumentGenerationCount; }
    public void setActiveDocumentGenerationCount(long activeDocumentGenerationCount) { this.activeDocumentGenerationCount = activeDocumentGenerationCount; }
}

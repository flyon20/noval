package com.novelanalyzer.modules.knowledge.vo;

import java.util.LinkedHashMap;
import java.util.Map;

public class ProjectMemoryOverviewVO {
    private Long projectId;
    private Long workId;
    private long activeChapterCount;
    private Integer chapterFrom;
    private Integer chapterTo;
    private long indexedDocumentCount;
    private long characterStateCount;
    private long worldRuleCount;
    private long foreshadowingCount;
    private Map<String, Long> foreshadowingStatusCounts = new LinkedHashMap<>();
    private long timelineEventCount;
    private long storyNodeCount;
    private long storyEdgeCount;
    private long pendingExtractionCount;
    private long longFormFactCount;
    private long pendingLongFormFactCount;
    private Map<String, Long> longFormFactStatusCounts = new LinkedHashMap<>();
    private long summaryNodeCount;
    private long summaryCoveredChapterCount;
    private String summaryCoverageStatus;
    private Map<String, Long> summaryNodeTypeCounts = new LinkedHashMap<>();
    private boolean recognizedRecordsOnly;
    private String corpusFingerprint;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getWorkId() { return workId; }
    public void setWorkId(Long workId) { this.workId = workId; }
    public long getActiveChapterCount() { return activeChapterCount; }
    public void setActiveChapterCount(long activeChapterCount) { this.activeChapterCount = activeChapterCount; }
    public Integer getChapterFrom() { return chapterFrom; }
    public void setChapterFrom(Integer chapterFrom) { this.chapterFrom = chapterFrom; }
    public Integer getChapterTo() { return chapterTo; }
    public void setChapterTo(Integer chapterTo) { this.chapterTo = chapterTo; }
    public long getIndexedDocumentCount() { return indexedDocumentCount; }
    public void setIndexedDocumentCount(long indexedDocumentCount) { this.indexedDocumentCount = indexedDocumentCount; }
    public long getCharacterStateCount() { return characterStateCount; }
    public void setCharacterStateCount(long characterStateCount) { this.characterStateCount = characterStateCount; }
    public long getWorldRuleCount() { return worldRuleCount; }
    public void setWorldRuleCount(long worldRuleCount) { this.worldRuleCount = worldRuleCount; }
    public long getForeshadowingCount() { return foreshadowingCount; }
    public void setForeshadowingCount(long foreshadowingCount) { this.foreshadowingCount = foreshadowingCount; }
    public Map<String, Long> getForeshadowingStatusCounts() { return foreshadowingStatusCounts; }
    public void setForeshadowingStatusCounts(Map<String, Long> foreshadowingStatusCounts) {
        this.foreshadowingStatusCounts = foreshadowingStatusCounts == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(foreshadowingStatusCounts);
    }
    public long getTimelineEventCount() { return timelineEventCount; }
    public void setTimelineEventCount(long timelineEventCount) { this.timelineEventCount = timelineEventCount; }
    public long getStoryNodeCount() { return storyNodeCount; }
    public void setStoryNodeCount(long storyNodeCount) { this.storyNodeCount = storyNodeCount; }
    public long getStoryEdgeCount() { return storyEdgeCount; }
    public void setStoryEdgeCount(long storyEdgeCount) { this.storyEdgeCount = storyEdgeCount; }
    public long getPendingExtractionCount() { return pendingExtractionCount; }
    public void setPendingExtractionCount(long pendingExtractionCount) { this.pendingExtractionCount = pendingExtractionCount; }
    public long getLongFormFactCount() { return longFormFactCount; }
    public void setLongFormFactCount(long longFormFactCount) { this.longFormFactCount = longFormFactCount; }
    public long getPendingLongFormFactCount() { return pendingLongFormFactCount; }
    public void setPendingLongFormFactCount(long pendingLongFormFactCount) { this.pendingLongFormFactCount = pendingLongFormFactCount; }
    public Map<String, Long> getLongFormFactStatusCounts() { return longFormFactStatusCounts; }
    public void setLongFormFactStatusCounts(Map<String, Long> longFormFactStatusCounts) {
        this.longFormFactStatusCounts = longFormFactStatusCounts == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(longFormFactStatusCounts);
    }
    public long getSummaryNodeCount() { return summaryNodeCount; }
    public void setSummaryNodeCount(long summaryNodeCount) { this.summaryNodeCount = summaryNodeCount; }
    public long getSummaryCoveredChapterCount() { return summaryCoveredChapterCount; }
    public void setSummaryCoveredChapterCount(long summaryCoveredChapterCount) { this.summaryCoveredChapterCount = summaryCoveredChapterCount; }
    public String getSummaryCoverageStatus() { return summaryCoverageStatus; }
    public void setSummaryCoverageStatus(String summaryCoverageStatus) { this.summaryCoverageStatus = summaryCoverageStatus; }
    public Map<String, Long> getSummaryNodeTypeCounts() { return summaryNodeTypeCounts; }
    public void setSummaryNodeTypeCounts(Map<String, Long> summaryNodeTypeCounts) {
        this.summaryNodeTypeCounts = summaryNodeTypeCounts == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(summaryNodeTypeCounts);
    }
    public boolean isRecognizedRecordsOnly() { return recognizedRecordsOnly; }
    public void setRecognizedRecordsOnly(boolean recognizedRecordsOnly) { this.recognizedRecordsOnly = recognizedRecordsOnly; }
    public String getCorpusFingerprint() { return corpusFingerprint; }
    public void setCorpusFingerprint(String corpusFingerprint) { this.corpusFingerprint = corpusFingerprint; }
}

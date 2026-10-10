package com.novelanalyzer.modules.knowledge.vo;

public class AiMemoryVO {

    private Long id;
    private Long userId;
    private Long projectId;
    private String conversationId;
    private String scope;
    private String memoryType;
    private String content;
    private String summary;
    private Double confidence;
    private String status;
    private String lifecycleStatus;
    private String legacyStatus;
    private String sourceTraceId;
    private String factKey;
    private String candidateKey;
    private String provenanceJson;
    private String evidenceJson;
    private String sourceEvidenceIdsJson;
    private String sourceChapterVersionsJson;
    private String indexGeneration;
    private String extractorVersion;
    private Long supersedesId;
    private Long conflictsWithId;
    private Long confirmedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public String getLegacyStatus() {
        return legacyStatus;
    }

    public void setLegacyStatus(String legacyStatus) {
        this.legacyStatus = legacyStatus;
    }

    public String getSourceTraceId() {
        return sourceTraceId;
    }

    public void setSourceTraceId(String sourceTraceId) {
        this.sourceTraceId = sourceTraceId;
    }

    public String getFactKey() {
        return factKey;
    }

    public void setFactKey(String factKey) {
        this.factKey = factKey;
    }

    public String getCandidateKey() {
        return candidateKey;
    }

    public void setCandidateKey(String candidateKey) {
        this.candidateKey = candidateKey;
    }

    public String getProvenanceJson() {
        return provenanceJson;
    }

    public void setProvenanceJson(String provenanceJson) {
        this.provenanceJson = provenanceJson;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public String getSourceEvidenceIdsJson() {
        return sourceEvidenceIdsJson;
    }

    public void setSourceEvidenceIdsJson(String sourceEvidenceIdsJson) {
        this.sourceEvidenceIdsJson = sourceEvidenceIdsJson;
    }

    public String getSourceChapterVersionsJson() {
        return sourceChapterVersionsJson;
    }

    public void setSourceChapterVersionsJson(String sourceChapterVersionsJson) {
        this.sourceChapterVersionsJson = sourceChapterVersionsJson;
    }

    public String getIndexGeneration() {
        return indexGeneration;
    }

    public void setIndexGeneration(String indexGeneration) {
        this.indexGeneration = indexGeneration;
    }

    public String getExtractorVersion() {
        return extractorVersion;
    }

    public void setExtractorVersion(String extractorVersion) {
        this.extractorVersion = extractorVersion;
    }

    public Long getSupersedesId() {
        return supersedesId;
    }

    public void setSupersedesId(Long supersedesId) {
        this.supersedesId = supersedesId;
    }

    public Long getConflictsWithId() {
        return conflictsWithId;
    }

    public void setConflictsWithId(Long conflictsWithId) {
        this.conflictsWithId = conflictsWithId;
    }

    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }
}

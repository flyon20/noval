package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AiMemoryCandidateRequest {

    @NotNull(message = "userId is required")
    private Long userId;
    private Long projectId;
    private String conversationId;
    @NotBlank(message = "scope is required")
    private String scope;
    @NotBlank(message = "memoryType is required")
    private String memoryType;
    @NotBlank(message = "content is required")
    private String content;
    private String summary;
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence = 0.0d;
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
    @Min(1)
    @Max(365)
    private Integer ttlDays = 30;

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

    public Integer getTtlDays() {
        return ttlDays;
    }

    public void setTtlDays(Integer ttlDays) {
        this.ttlDays = ttlDays;
    }
}

package com.novelanalyzer.modules.knowledge.vo;

public class SkillCandidateVO {
    private Long id;
    private String skillId;
    private String title;
    private String description;
    private String content;
    private String status;
    private String lifecycleStatus;
    private String legacyStatus;
    private String version;
    private String contentHash;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String requestedCapabilitiesJson;
    private String skillMetadataJson;
    private String rolloutPolicyJson;
    private String evalStatus;
    private String evalResultJson;
    private Double requiredToolPassRate;
    private Double evidencePassRate;
    private Double faithfulnessPassRate;
    private String reviewNote;
    private String sourceTraceId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public void setInputSchemaJson(String inputSchemaJson) {
        this.inputSchemaJson = inputSchemaJson;
    }

    public String getOutputSchemaJson() {
        return outputSchemaJson;
    }

    public void setOutputSchemaJson(String outputSchemaJson) {
        this.outputSchemaJson = outputSchemaJson;
    }

    public String getRequestedCapabilitiesJson() {
        return requestedCapabilitiesJson;
    }

    public void setRequestedCapabilitiesJson(String requestedCapabilitiesJson) {
        this.requestedCapabilitiesJson = requestedCapabilitiesJson;
    }

    public String getSkillMetadataJson() {
        return skillMetadataJson;
    }

    public void setSkillMetadataJson(String skillMetadataJson) {
        this.skillMetadataJson = skillMetadataJson;
    }

    public String getRolloutPolicyJson() {
        return rolloutPolicyJson;
    }

    public void setRolloutPolicyJson(String rolloutPolicyJson) {
        this.rolloutPolicyJson = rolloutPolicyJson;
    }

    public String getEvalStatus() {
        return evalStatus;
    }

    public void setEvalStatus(String evalStatus) {
        this.evalStatus = evalStatus;
    }

    public String getEvalResultJson() {
        return evalResultJson;
    }

    public void setEvalResultJson(String evalResultJson) {
        this.evalResultJson = evalResultJson;
    }

    public Double getRequiredToolPassRate() {
        return requiredToolPassRate;
    }

    public void setRequiredToolPassRate(Double requiredToolPassRate) {
        this.requiredToolPassRate = requiredToolPassRate;
    }

    public Double getEvidencePassRate() {
        return evidencePassRate;
    }

    public void setEvidencePassRate(Double evidencePassRate) {
        this.evidencePassRate = evidencePassRate;
    }

    public Double getFaithfulnessPassRate() {
        return faithfulnessPassRate;
    }

    public void setFaithfulnessPassRate(Double faithfulnessPassRate) {
        this.faithfulnessPassRate = faithfulnessPassRate;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public String getSourceTraceId() {
        return sourceTraceId;
    }

    public void setSourceTraceId(String sourceTraceId) {
        this.sourceTraceId = sourceTraceId;
    }
}

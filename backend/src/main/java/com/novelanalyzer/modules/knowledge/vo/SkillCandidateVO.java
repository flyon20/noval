package com.novelanalyzer.modules.knowledge.vo;

public class SkillCandidateVO {
    private Long id;
    private String skillId;
    private String title;
    private String content;
    private String status;
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

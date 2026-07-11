package com.novelanalyzer.modules.knowledge.dto;

public class SkillCandidateCreateRequest {
    private String skillId;
    private String title;
    private String content;
    private String evalResultJson;
    private Double requiredToolPassRate;
    private Double evidencePassRate;
    private Double faithfulnessPassRate;
    private String sourceTraceId;

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

    public String getSourceTraceId() {
        return sourceTraceId;
    }

    public void setSourceTraceId(String sourceTraceId) {
        this.sourceTraceId = sourceTraceId;
    }
}

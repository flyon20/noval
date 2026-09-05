package com.novelanalyzer.modules.knowledge.dto;

public class ProjectExtractionReviewRequest {
    private String decision;
    private String payloadJson;
    private String reviewNote;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}

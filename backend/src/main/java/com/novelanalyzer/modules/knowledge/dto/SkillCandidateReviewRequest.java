package com.novelanalyzer.modules.knowledge.dto;

public class SkillCandidateReviewRequest {
    private String decision;
    private String note;

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}

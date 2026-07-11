package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class GoldenCandidateDraftVO {
    private Long traceRecordId;
    private String traceId;
    private String question;
    private String answer;
    private String traceSummary;
    private List<String> selectedSkills = new ArrayList<>();
    private List<String> selectedTools = new ArrayList<>();
    private String evidenceContract;
    private String status;

    public Long getTraceRecordId() {
        return traceRecordId;
    }

    public void setTraceRecordId(Long traceRecordId) {
        this.traceRecordId = traceRecordId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getTraceSummary() {
        return traceSummary;
    }

    public void setTraceSummary(String traceSummary) {
        this.traceSummary = traceSummary;
    }

    public List<String> getSelectedSkills() {
        return selectedSkills;
    }

    public void setSelectedSkills(List<String> selectedSkills) {
        this.selectedSkills = selectedSkills == null ? new ArrayList<>() : selectedSkills;
    }

    public List<String> getSelectedTools() {
        return selectedTools;
    }

    public void setSelectedTools(List<String> selectedTools) {
        this.selectedTools = selectedTools == null ? new ArrayList<>() : selectedTools;
    }

    public String getEvidenceContract() {
        return evidenceContract;
    }

    public void setEvidenceContract(String evidenceContract) {
        this.evidenceContract = evidenceContract;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

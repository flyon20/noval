package com.novelanalyzer.modules.knowledge.vo;

import java.time.LocalDateTime;

public class KnowledgeAgentTraceVO {
    private Long id;
    private String traceId;
    private Long userId;
    private Long projectId;
    private String conversationId;
    private String question;
    private String status;
    private String taskGraph;
    private String toolRuns;
    private String evidencePack;
    private String perspectiveResults;
    private String resultJson;
    private String intentDecision;
    private String contextUsed;
    private String memoryUsed;
    private String memoryDiagnostics;
    private String retrievalDiagnostics;
    private String sourcePolicy;
    private String supervisorDecision;
    private String memoryCandidates;
    private String mcpToolCalls;
    private String toolPermissionDecisions;
    private String evidenceContract;
    private String selectedSnapshotGroup;
    private String rejectedSnapshotGroups;
    private String specialistAgentResults;
    private String skillMediation;
    private String skillBom;
    private String selectedExperts;
    private String expertRouter;
    private String finalAnswerBoundary;
    private String snapshotTime;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTaskGraph() {
        return taskGraph;
    }

    public void setTaskGraph(String taskGraph) {
        this.taskGraph = taskGraph;
    }

    public String getToolRuns() {
        return toolRuns;
    }

    public void setToolRuns(String toolRuns) {
        this.toolRuns = toolRuns;
    }

    public String getEvidencePack() {
        return evidencePack;
    }

    public void setEvidencePack(String evidencePack) {
        this.evidencePack = evidencePack;
    }

    public String getPerspectiveResults() {
        return perspectiveResults;
    }

    public void setPerspectiveResults(String perspectiveResults) {
        this.perspectiveResults = perspectiveResults;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getContextUsed() {
        return contextUsed;
    }

    public String getIntentDecision() {
        return intentDecision;
    }

    public void setIntentDecision(String intentDecision) {
        this.intentDecision = intentDecision;
    }

    public void setContextUsed(String contextUsed) {
        this.contextUsed = contextUsed;
    }

    public String getMemoryUsed() {
        return memoryUsed;
    }

    public void setMemoryUsed(String memoryUsed) {
        this.memoryUsed = memoryUsed;
    }

    public String getMemoryDiagnostics() {
        return memoryDiagnostics;
    }

    public void setMemoryDiagnostics(String memoryDiagnostics) {
        this.memoryDiagnostics = memoryDiagnostics;
    }

    public String getRetrievalDiagnostics() {
        return retrievalDiagnostics;
    }

    public void setRetrievalDiagnostics(String retrievalDiagnostics) {
        this.retrievalDiagnostics = retrievalDiagnostics;
    }

    public String getSourcePolicy() {
        return sourcePolicy;
    }

    public void setSourcePolicy(String sourcePolicy) {
        this.sourcePolicy = sourcePolicy;
    }

    public String getSupervisorDecision() {
        return supervisorDecision;
    }

    public void setSupervisorDecision(String supervisorDecision) {
        this.supervisorDecision = supervisorDecision;
    }

    public String getMemoryCandidates() {
        return memoryCandidates;
    }

    public void setMemoryCandidates(String memoryCandidates) {
        this.memoryCandidates = memoryCandidates;
    }

    public String getMcpToolCalls() {
        return mcpToolCalls;
    }

    public void setMcpToolCalls(String mcpToolCalls) {
        this.mcpToolCalls = mcpToolCalls;
    }

    public String getToolPermissionDecisions() {
        return toolPermissionDecisions;
    }

    public void setToolPermissionDecisions(String toolPermissionDecisions) {
        this.toolPermissionDecisions = toolPermissionDecisions;
    }

    public String getEvidenceContract() {
        return evidenceContract;
    }

    public void setEvidenceContract(String evidenceContract) {
        this.evidenceContract = evidenceContract;
    }

    public String getSelectedSnapshotGroup() {
        return selectedSnapshotGroup;
    }

    public void setSelectedSnapshotGroup(String selectedSnapshotGroup) {
        this.selectedSnapshotGroup = selectedSnapshotGroup;
    }

    public String getRejectedSnapshotGroups() {
        return rejectedSnapshotGroups;
    }

    public void setRejectedSnapshotGroups(String rejectedSnapshotGroups) {
        this.rejectedSnapshotGroups = rejectedSnapshotGroups;
    }

    public String getSpecialistAgentResults() {
        return specialistAgentResults;
    }

    public void setSpecialistAgentResults(String specialistAgentResults) {
        this.specialistAgentResults = specialistAgentResults;
    }

    public String getSkillMediation() {
        return skillMediation;
    }

    public void setSkillMediation(String skillMediation) {
        this.skillMediation = skillMediation;
    }

    public String getSkillBom() {
        return skillBom;
    }

    public void setSkillBom(String skillBom) {
        this.skillBom = skillBom;
    }

    public String getSelectedExperts() {
        return selectedExperts;
    }

    public void setSelectedExperts(String selectedExperts) {
        this.selectedExperts = selectedExperts;
    }

    public String getExpertRouter() {
        return expertRouter;
    }

    public void setExpertRouter(String expertRouter) {
        this.expertRouter = expertRouter;
    }

    public String getFinalAnswerBoundary() {
        return finalAnswerBoundary;
    }

    public void setFinalAnswerBoundary(String finalAnswerBoundary) {
        this.finalAnswerBoundary = finalAnswerBoundary;
    }

    public String getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(String snapshotTime) {
        this.snapshotTime = snapshotTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

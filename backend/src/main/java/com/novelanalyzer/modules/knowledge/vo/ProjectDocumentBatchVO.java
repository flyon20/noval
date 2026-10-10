package com.novelanalyzer.modules.knowledge.vo;

import java.time.LocalDateTime;

public class ProjectDocumentBatchVO {
    private Long batchId;
    private Long userId;
    private Long projectId;
    private Long workId;
    private String status;
    private String statusLabel;
    private String stage;
    private Integer progress;
    private Integer totalFiles;
    private Integer storedFiles;
    private Integer parsedFiles;
    private Integer indexedFiles;
    private Integer skippedFiles;
    private Integer failedFiles;
    private Integer pendingQuestions;
    private Long totalBytes;
    private Integer attempt;
    private Integer maxAttempts;
    private String errorCode;
    private String errorSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getWorkId() { return workId; }
    public void setWorkId(Long workId) { this.workId = workId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public Integer getTotalFiles() { return totalFiles; }
    public void setTotalFiles(Integer totalFiles) { this.totalFiles = totalFiles; }
    public Integer getStoredFiles() { return storedFiles; }
    public void setStoredFiles(Integer storedFiles) { this.storedFiles = storedFiles; }
    public Integer getParsedFiles() { return parsedFiles; }
    public void setParsedFiles(Integer parsedFiles) { this.parsedFiles = parsedFiles; }
    public Integer getIndexedFiles() { return indexedFiles; }
    public void setIndexedFiles(Integer indexedFiles) { this.indexedFiles = indexedFiles; }
    public Integer getSkippedFiles() { return skippedFiles; }
    public void setSkippedFiles(Integer skippedFiles) { this.skippedFiles = skippedFiles; }
    public Integer getFailedFiles() { return failedFiles; }
    public void setFailedFiles(Integer failedFiles) { this.failedFiles = failedFiles; }
    public Integer getPendingQuestions() { return pendingQuestions; }
    public void setPendingQuestions(Integer pendingQuestions) { this.pendingQuestions = pendingQuestions; }
    public Long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(Long totalBytes) { this.totalBytes = totalBytes; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

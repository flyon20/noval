package com.novelanalyzer.modules.data.vo;

import java.util.List;

public class AnalysisHistorySummaryVO {

    private Long id;
    private Long bookId;
    private String bookName;
    private String analysisType;
    private Integer chapterCount;
    private String modelName;
    private String channelCode;
    private String boardCode;
    private Long snapshotId;
    private Integer tokenUsed;
    private Long costTime;
    private String summaryPreview;
    private String createdAt;
    private List<String> matchedFields = List.of();
    private List<String> matchSnippets = List.of();
    private Double matchScore;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public String getAnalysisType() {
        return analysisType;
    }

    public void setAnalysisType(String analysisType) {
        this.analysisType = analysisType;
    }

    public Integer getChapterCount() {
        return chapterCount;
    }

    public void setChapterCount(Integer chapterCount) {
        this.chapterCount = chapterCount;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getBoardCode() {
        return boardCode;
    }

    public void setBoardCode(String boardCode) {
        this.boardCode = boardCode;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Integer getTokenUsed() {
        return tokenUsed;
    }

    public void setTokenUsed(Integer tokenUsed) {
        this.tokenUsed = tokenUsed;
    }

    public Long getCostTime() {
        return costTime;
    }

    public void setCostTime(Long costTime) {
        this.costTime = costTime;
    }

    public String getSummaryPreview() {
        return summaryPreview;
    }

    public void setSummaryPreview(String summaryPreview) {
        this.summaryPreview = summaryPreview;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getMatchedFields() {
        return matchedFields;
    }

    public void setMatchedFields(List<String> matchedFields) {
        this.matchedFields = matchedFields == null ? List.of() : matchedFields;
    }

    public List<String> getMatchSnippets() {
        return matchSnippets;
    }

    public void setMatchSnippets(List<String> matchSnippets) {
        this.matchSnippets = matchSnippets == null ? List.of() : matchSnippets;
    }

    public Double getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(Double matchScore) {
        this.matchScore = matchScore;
    }
}

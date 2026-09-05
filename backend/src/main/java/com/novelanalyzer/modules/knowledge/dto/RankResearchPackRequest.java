package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public class RankResearchPackRequest {

    @NotNull(message = "user scope is required")
    @Positive(message = "user scope is invalid")
    private Long userId;
    @NotBlank
    private String platform;
    private String channelCode;
    private String boardCode;
    private String category;
    private Integer rankNo;
    private Integer limit;
    private Integer chapterLimitPerBook;
    private String freshness = "latest";
    private Boolean allowHistorical = false;
    private Integer timeWindowDays;
    private LocalDate snapshotStartDate;
    private LocalDate snapshotEndDate;
    private Boolean requireSnapshotTime = true;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getRankNo() {
        return rankNo;
    }

    public void setRankNo(Integer rankNo) {
        this.rankNo = rankNo;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getChapterLimitPerBook() {
        return chapterLimitPerBook;
    }

    public void setChapterLimitPerBook(Integer chapterLimitPerBook) {
        this.chapterLimitPerBook = chapterLimitPerBook;
    }

    public String getFreshness() {
        return freshness;
    }

    public void setFreshness(String freshness) {
        this.freshness = freshness;
    }

    public Boolean getAllowHistorical() {
        return allowHistorical;
    }

    public void setAllowHistorical(Boolean allowHistorical) {
        this.allowHistorical = allowHistorical;
    }

    public Integer getTimeWindowDays() {
        return timeWindowDays;
    }

    public void setTimeWindowDays(Integer timeWindowDays) {
        this.timeWindowDays = timeWindowDays;
    }

    public LocalDate getSnapshotStartDate() {
        return snapshotStartDate;
    }

    public void setSnapshotStartDate(LocalDate snapshotStartDate) {
        this.snapshotStartDate = snapshotStartDate;
    }

    public LocalDate getSnapshotEndDate() {
        return snapshotEndDate;
    }

    public void setSnapshotEndDate(LocalDate snapshotEndDate) {
        this.snapshotEndDate = snapshotEndDate;
    }

    public Boolean getRequireSnapshotTime() {
        return requireSnapshotTime;
    }

    public void setRequireSnapshotTime(Boolean requireSnapshotTime) {
        this.requireSnapshotTime = requireSnapshotTime;
    }
}

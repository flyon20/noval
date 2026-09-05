package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public class RankLookupRequest {

    @NotBlank(message = "platform is required")
    private String platform;
    private String channelCode;
    private String boardCode;
    private String category;
    private Integer rankNo;
    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = 100, message = "limit must be at most 100")
    private Integer limit = 10;
    private Boolean latestOnly = true;
    private String freshness = "latest";
    private Boolean allowHistorical = false;
    @Min(value = 1, message = "timeWindowDays must be at least 1")
    @Max(value = 365, message = "timeWindowDays must be at most 365")
    private Integer timeWindowDays;
    private LocalDate snapshotStartDate;
    private LocalDate snapshotEndDate;
    private Boolean requireSnapshotTime = true;

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

    public Boolean getLatestOnly() {
        return latestOnly;
    }

    public void setLatestOnly(Boolean latestOnly) {
        this.latestOnly = latestOnly;
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

package com.novelanalyzer.modules.crawler.vo;

import java.time.LocalDateTime;

public class RankRefreshResultVO {

    private String channelCode;
    private String boardCode;
    private Long snapshotId;
    private LocalDateTime snapshotTime;
    private Integer total;
    private Boolean reused;
    private Boolean refreshLimited;
    private Boolean analysisTriggered;

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

    public LocalDateTime getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(LocalDateTime snapshotTime) {
        this.snapshotTime = snapshotTime;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Boolean getReused() {
        return reused;
    }

    public void setReused(Boolean reused) {
        this.reused = reused;
    }

    public Boolean getRefreshLimited() {
        return refreshLimited;
    }

    public void setRefreshLimited(Boolean refreshLimited) {
        this.refreshLimited = refreshLimited;
    }

    public Boolean getAnalysisTriggered() {
        return analysisTriggered;
    }

    public void setAnalysisTriggered(Boolean analysisTriggered) {
        this.analysisTriggered = analysisTriggered;
    }
}

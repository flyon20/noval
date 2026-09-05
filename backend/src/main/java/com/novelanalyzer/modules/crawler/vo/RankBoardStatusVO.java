package com.novelanalyzer.modules.crawler.vo;

import java.time.LocalDateTime;

public class RankBoardStatusVO {

    private Long snapshotId;
    private LocalDateTime snapshotTime;
    private Integer total;
    private String freshness;
    private Long ageHours;
    private Boolean historicalReference;
    private Boolean refreshScheduled;

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public LocalDateTime getSnapshotTime() { return snapshotTime; }
    public void setSnapshotTime(LocalDateTime snapshotTime) { this.snapshotTime = snapshotTime; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public String getFreshness() { return freshness; }
    public void setFreshness(String freshness) { this.freshness = freshness; }
    public Long getAgeHours() { return ageHours; }
    public void setAgeHours(Long ageHours) { this.ageHours = ageHours; }
    public Boolean getHistoricalReference() { return historicalReference; }
    public void setHistoricalReference(Boolean historicalReference) { this.historicalReference = historicalReference; }
    public Boolean getRefreshScheduled() { return refreshScheduled; }
    public void setRefreshScheduled(Boolean refreshScheduled) { this.refreshScheduled = refreshScheduled; }
}

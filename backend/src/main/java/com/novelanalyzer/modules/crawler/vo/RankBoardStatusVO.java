package com.novelanalyzer.modules.crawler.vo;

import java.time.LocalDateTime;

public class RankBoardStatusVO {

    private Long snapshotId;
    private LocalDateTime snapshotTime;
    private Integer total;

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
}

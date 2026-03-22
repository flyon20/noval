package com.novelanalyzer.modules.crawler.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class RankPageVO {

    private Long snapshotId;
    private LocalDateTime snapshotTime;
    private Integer total;
    private Integer page;
    private Integer pageSize;
    private List<RankBookItemVO> items = new ArrayList<>();

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

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public List<RankBookItemVO> getItems() {
        return items;
    }

    public void setItems(List<RankBookItemVO> items) {
        this.items = items;
    }
}

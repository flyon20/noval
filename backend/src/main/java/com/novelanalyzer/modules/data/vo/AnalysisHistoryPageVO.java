package com.novelanalyzer.modules.data.vo;

import java.util.List;

public class AnalysisHistoryPageVO {

    private List<AnalysisHistorySummaryVO> items = List.of();
    private Integer page;
    private Integer pageSize;
    private Long total;
    private Boolean hasNext;

    public List<AnalysisHistorySummaryVO> getItems() {
        return items;
    }

    public void setItems(List<AnalysisHistorySummaryVO> items) {
        this.items = items == null ? List.of() : items;
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

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Boolean getHasNext() {
        return hasNext;
    }

    public void setHasNext(Boolean hasNext) {
        this.hasNext = hasNext;
    }
}

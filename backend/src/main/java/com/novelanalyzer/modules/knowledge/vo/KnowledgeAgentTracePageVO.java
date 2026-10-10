package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeAgentTracePageVO {
    private Integer page;
    private Integer pageSize;
    private Long total;
    private Boolean hasNext;
    private List<KnowledgeAgentTraceSummaryVO> items = new ArrayList<>();

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

    public List<KnowledgeAgentTraceSummaryVO> getItems() {
        return items;
    }

    public void setItems(List<KnowledgeAgentTraceSummaryVO> items) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }
}

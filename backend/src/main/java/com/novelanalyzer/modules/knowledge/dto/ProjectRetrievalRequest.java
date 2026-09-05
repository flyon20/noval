package com.novelanalyzer.modules.knowledge.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectRetrievalRequest {
    private Long userId;
    private Long projectId;
    private Long workId;
    private String query;
    private String intent;
    private Integer chapterFrom;
    private Integer chapterTo;
    private List<String> channels = new ArrayList<>();
    private Map<String, Object> filters = new LinkedHashMap<>();
    private Map<String, Double> weights = new LinkedHashMap<>();
    private Integer limit;
    private Boolean deep;
    private Integer graphBudgetMillis;
    private Integer timeoutMillis;
    private String rerankPolicy;
    private List<String> entities = new ArrayList<>();

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getWorkId() { return workId; }
    public void setWorkId(Long workId) { this.workId = workId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public Integer getChapterFrom() { return chapterFrom; }
    public void setChapterFrom(Integer chapterFrom) { this.chapterFrom = chapterFrom; }
    public Integer getChapterTo() { return chapterTo; }
    public void setChapterTo(Integer chapterTo) { this.chapterTo = chapterTo; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels); }
    public Map<String, Object> getFilters() { return filters; }
    public void setFilters(Map<String, Object> filters) { this.filters = filters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(filters); }
    public Map<String, Double> getWeights() { return weights; }
    public void setWeights(Map<String, Double> weights) { this.weights = weights == null ? new LinkedHashMap<>() : new LinkedHashMap<>(weights); }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
    public Boolean getDeep() { return deep; }
    public void setDeep(Boolean deep) { this.deep = deep; }
    public Integer getGraphBudgetMillis() { return graphBudgetMillis; }
    public void setGraphBudgetMillis(Integer graphBudgetMillis) { this.graphBudgetMillis = graphBudgetMillis; }
    public Integer getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(Integer timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public String getRerankPolicy() { return rerankPolicy; }
    public void setRerankPolicy(String rerankPolicy) { this.rerankPolicy = rerankPolicy; }
    public List<String> getEntities() { return entities; }
    public void setEntities(List<String> entities) { this.entities = entities == null ? new ArrayList<>() : new ArrayList<>(entities); }
}

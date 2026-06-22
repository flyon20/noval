package com.novelanalyzer.modules.knowledge.vo;

import java.util.LinkedHashMap;
import java.util.Map;

public class KnowledgeProjectMemoryVO {

    private Long projectId;
    private Long userId;
    private Map<String, String> memories = new LinkedHashMap<>();

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Map<String, String> getMemories() {
        return memories;
    }

    public void setMemories(Map<String, String> memories) {
        this.memories = memories == null ? new LinkedHashMap<>() : new LinkedHashMap<>(memories);
    }
}

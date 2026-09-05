package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class SkillShortcutVO {
    private String skillId;
    private String title;
    private String description;
    private List<String> appliesTo = new ArrayList<>();

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getAppliesTo() {
        return appliesTo;
    }

    public void setAppliesTo(List<String> appliesTo) {
        this.appliesTo = appliesTo == null ? new ArrayList<>() : new ArrayList<>(appliesTo);
    }
}

package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class SkillGovernanceDashboardVO {
    private List<RuntimeSkillVO> runtimeSkills = new ArrayList<>();
    private SkillCandidatePageVO candidates;

    public List<RuntimeSkillVO> getRuntimeSkills() {
        return runtimeSkills;
    }

    public void setRuntimeSkills(List<RuntimeSkillVO> runtimeSkills) {
        this.runtimeSkills = runtimeSkills == null ? new ArrayList<>() : new ArrayList<>(runtimeSkills);
    }

    public SkillCandidatePageVO getCandidates() {
        return candidates;
    }

    public void setCandidates(SkillCandidatePageVO candidates) {
        this.candidates = candidates;
    }
}

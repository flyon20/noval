package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.List;

public class RuntimeSkillVO {
    private Long candidateId;
    private String skillId;
    private String version;
    private String title;
    private String content;
    private String promptFragment;
    private String guardrails;
    private String negativeRules;
    private String outputContract;
    private String source;
    private List<String> intents = new ArrayList<>();
    private List<String> triggers = new ArrayList<>();
    private List<String> allowedTools = new ArrayList<>();
    private List<String> requiredEvidence = new ArrayList<>();

    public Long getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(Long candidateId) {
        this.candidateId = candidateId;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPromptFragment() {
        return promptFragment;
    }

    public void setPromptFragment(String promptFragment) {
        this.promptFragment = promptFragment;
    }

    public String getGuardrails() {
        return guardrails;
    }

    public void setGuardrails(String guardrails) {
        this.guardrails = guardrails;
    }

    public String getNegativeRules() {
        return negativeRules;
    }

    public void setNegativeRules(String negativeRules) {
        this.negativeRules = negativeRules;
    }

    public String getOutputContract() {
        return outputContract;
    }

    public void setOutputContract(String outputContract) {
        this.outputContract = outputContract;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<String> getIntents() {
        return intents;
    }

    public void setIntents(List<String> intents) {
        this.intents = intents == null ? new ArrayList<>() : new ArrayList<>(intents);
    }

    public List<String> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<String> triggers) {
        this.triggers = triggers == null ? new ArrayList<>() : new ArrayList<>(triggers);
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools == null ? new ArrayList<>() : new ArrayList<>(allowedTools);
    }

    public List<String> getRequiredEvidence() {
        return requiredEvidence;
    }

    public void setRequiredEvidence(List<String> requiredEvidence) {
        this.requiredEvidence = requiredEvidence == null ? new ArrayList<>() : new ArrayList<>(requiredEvidence);
    }
}

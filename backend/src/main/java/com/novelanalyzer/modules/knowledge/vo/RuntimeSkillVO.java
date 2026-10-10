package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RuntimeSkillVO {
    private Long candidateId;
    private String skillId;
    private String version;
    private String title;
    private String description;
    private String content;
    private String status;
    private String contentHash;
    private String sourceTraceId;
    private Map<String, Object> inputSchema = new LinkedHashMap<>();
    private Map<String, Object> outputSchema = new LinkedHashMap<>();
    private Map<String, Object> rolloutPolicy = new LinkedHashMap<>();
    private Map<String, Object> skillMetadata = new LinkedHashMap<>();
    private String promptFragment;
    private String guardrails;
    private String negativeRules;
    private String outputContract;
    private String source;
    private List<String> intents = new ArrayList<>();
    private List<String> triggers = new ArrayList<>();
    private List<String> appliesTo = new ArrayList<>();
    private List<String> requestedCapabilities = new ArrayList<>();
    private List<String> requiredEvidence = new ArrayList<>();
    private List<String> qualityChecklist = new ArrayList<>();
    private List<String> examples = new ArrayList<>();

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getSourceTraceId() {
        return sourceTraceId;
    }

    public void setSourceTraceId(String sourceTraceId) {
        this.sourceTraceId = sourceTraceId;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputSchema);
    }

    public Map<String, Object> getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(Map<String, Object> outputSchema) {
        this.outputSchema = outputSchema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(outputSchema);
    }

    public Map<String, Object> getRolloutPolicy() {
        return rolloutPolicy;
    }

    public void setRolloutPolicy(Map<String, Object> rolloutPolicy) {
        this.rolloutPolicy = rolloutPolicy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rolloutPolicy);
    }

    public Map<String, Object> getSkillMetadata() {
        return skillMetadata;
    }

    public void setSkillMetadata(Map<String, Object> skillMetadata) {
        this.skillMetadata = skillMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(skillMetadata);
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

    public List<String> getAppliesTo() {
        return appliesTo;
    }

    public void setAppliesTo(List<String> appliesTo) {
        this.appliesTo = appliesTo == null ? new ArrayList<>() : new ArrayList<>(appliesTo);
    }

    public List<String> getRequestedCapabilities() {
        return requestedCapabilities;
    }

    public void setRequestedCapabilities(List<String> requestedCapabilities) {
        this.requestedCapabilities = requestedCapabilities == null
            ? new ArrayList<>()
            : new ArrayList<>(requestedCapabilities);
    }

    public List<String> getRequiredEvidence() {
        return requiredEvidence;
    }

    public void setRequiredEvidence(List<String> requiredEvidence) {
        this.requiredEvidence = requiredEvidence == null ? new ArrayList<>() : new ArrayList<>(requiredEvidence);
    }

    public List<String> getQualityChecklist() {
        return qualityChecklist;
    }

    public void setQualityChecklist(List<String> qualityChecklist) {
        this.qualityChecklist = qualityChecklist == null ? new ArrayList<>() : new ArrayList<>(qualityChecklist);
    }

    public List<String> getExamples() {
        return examples;
    }

    public void setExamples(List<String> examples) {
        this.examples = examples == null ? new ArrayList<>() : new ArrayList<>(examples);
    }
}

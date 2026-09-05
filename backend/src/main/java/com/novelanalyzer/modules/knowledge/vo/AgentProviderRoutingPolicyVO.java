package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentProviderRoutingPolicyVO {

    private Integer schemaVersion;
    private Boolean enabled;
    private List<String> orderedProfileKeys = new ArrayList<>();
    private Integer maxFailovers;
    private Integer cooldownSeconds;
    private Map<String, AgentProviderCircuitStateVO> circuitStates = new LinkedHashMap<>();

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getOrderedProfileKeys() {
        return orderedProfileKeys;
    }

    public void setOrderedProfileKeys(List<String> orderedProfileKeys) {
        this.orderedProfileKeys = orderedProfileKeys == null
            ? new ArrayList<>()
            : new ArrayList<>(orderedProfileKeys);
    }

    public Integer getMaxFailovers() {
        return maxFailovers;
    }

    public void setMaxFailovers(Integer maxFailovers) {
        this.maxFailovers = maxFailovers;
    }

    public Integer getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(Integer cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public Map<String, AgentProviderCircuitStateVO> getCircuitStates() {
        return circuitStates;
    }

    public void setCircuitStates(Map<String, AgentProviderCircuitStateVO> circuitStates) {
        this.circuitStates = circuitStates == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(circuitStates);
    }
}

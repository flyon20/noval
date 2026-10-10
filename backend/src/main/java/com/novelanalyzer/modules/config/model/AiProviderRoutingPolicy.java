package com.novelanalyzer.modules.config.model;

import java.util.ArrayList;
import java.util.List;

public class AiProviderRoutingPolicy {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int DEFAULT_COOLDOWN_SECONDS = 60;

    private Integer schemaVersion = CURRENT_SCHEMA_VERSION;
    private Boolean enabled = false;
    private List<String> orderedProfileKeys = new ArrayList<>();
    /**
     * How many times a run may switch to the next key in {@link #orderedProfileKeys}.
     * Valid range is 0..orderedProfileKeys.size(); {@code null} means "walk the whole
     * chain" and is normalized to orderedProfileKeys.size() - 1. An explicit 0 pins
     * routing to the first key, which disables failover without disabling routing.
     */
    private Integer maxFailovers;
    private Integer cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;

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

    public AiProviderRoutingPolicy copy() {
        AiProviderRoutingPolicy copy = new AiProviderRoutingPolicy();
        copy.setSchemaVersion(schemaVersion);
        copy.setEnabled(enabled);
        copy.setOrderedProfileKeys(orderedProfileKeys);
        copy.setMaxFailovers(maxFailovers);
        copy.setCooldownSeconds(cooldownSeconds);
        return copy;
    }
}

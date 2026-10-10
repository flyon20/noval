package com.novelanalyzer.modules.config.model;

/** Responses prompt-cache request contract for one concrete Provider profile. */
public class AiPromptCacheCapabilities {

    private String strategy;
    private String mode;
    private String retention;
    private String breakpoint;

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getRetention() {
        return retention;
    }

    public void setRetention(String retention) {
        this.retention = retention;
    }

    public String getBreakpoint() {
        return breakpoint;
    }

    public void setBreakpoint(String breakpoint) {
        this.breakpoint = breakpoint;
    }

    public AiPromptCacheCapabilities copy() {
        AiPromptCacheCapabilities copy = new AiPromptCacheCapabilities();
        copy.setStrategy(strategy);
        copy.setMode(mode);
        copy.setRetention(retention);
        copy.setBreakpoint(breakpoint);
        return copy;
    }
}

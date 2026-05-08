package com.novelanalyzer.modules.system.vo;

public class AuthPublicConfigVO {

    private boolean turnstileEnabled;
    private String turnstileSiteKey;

    public boolean isTurnstileEnabled() {
        return turnstileEnabled;
    }

    public void setTurnstileEnabled(boolean turnstileEnabled) {
        this.turnstileEnabled = turnstileEnabled;
    }

    public String getTurnstileSiteKey() {
        return turnstileSiteKey;
    }

    public void setTurnstileSiteKey(String turnstileSiteKey) {
        this.turnstileSiteKey = turnstileSiteKey;
    }
}

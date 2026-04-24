package com.novelanalyzer.modules.auth.vo;

public class SmsSendResponse {

    private String debugVerifyCode;
    private String smsOutId;

    public SmsSendResponse() {
    }

    public SmsSendResponse(String debugVerifyCode) {
        this.debugVerifyCode = debugVerifyCode;
    }

    public String getDebugVerifyCode() {
        return debugVerifyCode;
    }

    public void setDebugVerifyCode(String debugVerifyCode) {
        this.debugVerifyCode = debugVerifyCode;
    }

    public String getSmsOutId() {
        return smsOutId;
    }

    public void setSmsOutId(String smsOutId) {
        this.smsOutId = smsOutId;
    }
}

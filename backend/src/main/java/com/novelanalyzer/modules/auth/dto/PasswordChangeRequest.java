package com.novelanalyzer.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class PasswordChangeRequest {

    private String verifyMode;

    private String oldPassword;

    @NotBlank(message = "newPassword is required")
    private String newPassword;

    private String smsCode;
    private String smsOutId;

    public String getVerifyMode() {
        return verifyMode;
    }

    public void setVerifyMode(String verifyMode) {
        this.verifyMode = verifyMode;
    }

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getSmsCode() {
        return smsCode;
    }

    public void setSmsCode(String smsCode) {
        this.smsCode = smsCode;
    }

    public String getSmsOutId() {
        return smsOutId;
    }

    public void setSmsOutId(String smsOutId) {
        this.smsOutId = smsOutId;
    }
}

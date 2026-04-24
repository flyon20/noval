package com.novelanalyzer.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SmsLoginRequest {

    @NotBlank(message = "phone is required")
    @Pattern(regexp = "^1\\d{10}$", message = "phone format is invalid")
    private String phone;

    @NotBlank(message = "smsCode is required")
    private String smsCode;

    private String smsOutId;

    @Size(max = 100, message = "deviceLabel must be at most 100 characters")
    private String deviceLabel;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public void setDeviceLabel(String deviceLabel) {
        this.deviceLabel = deviceLabel;
    }
}

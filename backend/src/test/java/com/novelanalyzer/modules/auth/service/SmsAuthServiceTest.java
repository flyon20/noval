package com.novelanalyzer.modules.auth.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.dypnsapi.model.v20170525.CheckSmsVerifyCodeRequest;
import com.aliyuncs.dypnsapi.model.v20170525.CheckSmsVerifyCodeResponse;
import com.aliyuncs.dypnsapi.model.v20170525.SendSmsVerifyCodeRequest;
import com.aliyuncs.dypnsapi.model.v20170525.SendSmsVerifyCodeResponse;
import com.aliyuncs.exceptions.ClientException;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.SmsAuthProperties;
import com.novelanalyzer.modules.auth.model.SmsCodeLogEntity;
import com.novelanalyzer.modules.auth.repository.SmsCodeLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsAuthServiceTest {

    @Mock
    private SmsCodeLogRepository smsCodeLogRepository;

    @Mock
    private SmsRiskControlService smsRiskControlService;

    @Test
    void shouldTreatAliyunValidateFailAsBadRequest() throws Exception {
        SmsAuthService service = new SmsAuthService(buildProperties(), smsCodeLogRepository, smsRiskControlService);
        SmsCodeLogEntity log = buildActiveLog();
        when(smsCodeLogRepository.findRecentUnconsumed("13800138000", "REGISTER", 5)).thenReturn(java.util.List.of(log));

        try (MockedConstruction<DefaultAcsClient> mocked = mockConstruction(DefaultAcsClient.class, (mock, context) -> {
            when(mock.getAcsResponse(any(CheckSmsVerifyCodeRequest.class)))
                .thenThrow(new ClientException("isv.ValidateFail", "验证失败"));
        })) {
            assertThatThrownBy(() -> service.verifyCode("13800138000", "REGISTER", "09fhds", null, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException businessException = (BusinessException) ex;
                    assertThat(businessException.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST);
                    assertThat(businessException.getMessage()).isEqualTo("\u9a8c\u8bc1\u7801\u9519\u8bef\u6216\u5df2\u5931\u6548");
                });

            assertThat(mocked.constructed()).hasSize(1);
            verify(smsCodeLogRepository).markFailed(log.getOutId(), "VERIFY_FAILED", "验证失败");
            verify(smsCodeLogRepository, never()).markVerified(any(), any());
            verify(smsCodeLogRepository, never()).markConsumed(any());
        }
    }

    @Test
    void shouldTrimVerifyCodeBeforeCallingAliyun() throws Exception {
        SmsAuthService service = new SmsAuthService(buildProperties(), smsCodeLogRepository, smsRiskControlService);
        SmsCodeLogEntity log = buildActiveLog();
        when(smsCodeLogRepository.findRecentUnconsumed("13800138000", "REGISTER", 5)).thenReturn(java.util.List.of(log));

        CheckSmsVerifyCodeResponse response = new CheckSmsVerifyCodeResponse();
        response.setSuccess(true);
        CheckSmsVerifyCodeResponse.Model model = new CheckSmsVerifyCodeResponse.Model();
        model.setVerifyResult("PASS");
        response.setModel(model);

        try (MockedConstruction<DefaultAcsClient> mocked = mockConstruction(DefaultAcsClient.class, (mock, context) -> {
            when(mock.getAcsResponse(any(CheckSmsVerifyCodeRequest.class))).thenReturn(response);
        })) {
            service.verifyCode("13800138000", "REGISTER", " 09fhds  ", null, true);

            assertThat(mocked.constructed()).hasSize(1);
            ArgumentCaptor<CheckSmsVerifyCodeRequest> requestCaptor = ArgumentCaptor.forClass(CheckSmsVerifyCodeRequest.class);
            verify(mocked.constructed().get(0)).getAcsResponse(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getVerifyCode()).isEqualTo("09fhds");
            verify(smsCodeLogRepository).markVerified(log.getOutId(), "PASS");
            verify(smsCodeLogRepository).markConsumed(log.getOutId());
        }
    }

    @Test
    void shouldPreferSmsOutIdWhenVerifyingCode() throws Exception {
        SmsAuthService service = new SmsAuthService(buildProperties(), smsCodeLogRepository, smsRiskControlService);
        SmsCodeLogEntity log = buildActiveLog();
        when(smsCodeLogRepository.findByOutId("out-id-001")).thenReturn(Optional.of(log));

        CheckSmsVerifyCodeResponse response = new CheckSmsVerifyCodeResponse();
        response.setSuccess(true);
        CheckSmsVerifyCodeResponse.Model model = new CheckSmsVerifyCodeResponse.Model();
        model.setVerifyResult("PASS");
        response.setModel(model);

        try (MockedConstruction<DefaultAcsClient> mocked = mockConstruction(DefaultAcsClient.class, (mock, context) -> {
            when(mock.getAcsResponse(any(CheckSmsVerifyCodeRequest.class))).thenReturn(response);
        })) {
            service.verifyCode("13800138000", "REGISTER", "09fhds", "out-id-001", true);

            verify(smsCodeLogRepository).findByOutId("out-id-001");
            verify(smsCodeLogRepository, never()).findLatestActive("13800138000", "REGISTER");
            verify(smsCodeLogRepository).markVerified("out-id-001", "PASS");
            verify(smsCodeLogRepository).markConsumed("out-id-001");
            assertThat(mocked.constructed()).hasSize(1);
        }
    }

    @Test
    void shouldStillUseProvidedSmsOutIdWhenLocalLogCannotBeLoaded() throws Exception {
        SmsAuthService service = new SmsAuthService(buildProperties(), smsCodeLogRepository, smsRiskControlService);
        when(smsCodeLogRepository.findByOutId("out-id-001")).thenReturn(Optional.empty());
        when(smsCodeLogRepository.findRecentUnconsumed("13800138000", "REGISTER", 5)).thenReturn(java.util.List.of());

        CheckSmsVerifyCodeResponse response = new CheckSmsVerifyCodeResponse();
        response.setSuccess(true);
        CheckSmsVerifyCodeResponse.Model model = new CheckSmsVerifyCodeResponse.Model();
        model.setVerifyResult("PASS");
        response.setModel(model);

        try (MockedConstruction<DefaultAcsClient> mocked = mockConstruction(DefaultAcsClient.class, (mock, context) -> {
            when(mock.getAcsResponse(any(CheckSmsVerifyCodeRequest.class))).thenReturn(response);
        })) {
            service.verifyCode("13800138000", "REGISTER", "09fhds", "out-id-001", true);

            ArgumentCaptor<CheckSmsVerifyCodeRequest> requestCaptor = ArgumentCaptor.forClass(CheckSmsVerifyCodeRequest.class);
            verify(mocked.constructed().get(0)).getAcsResponse(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getOutId()).isEqualTo("out-id-001");
            verify(smsCodeLogRepository).markVerified("out-id-001", "PASS");
            verify(smsCodeLogRepository).markConsumed("out-id-001");
        }
    }

    @Test
    void shouldPreferProvidedSmsOutIdForAliyunVerificationEvenWhenFallingBackLocally() throws Exception {
        SmsAuthService service = new SmsAuthService(buildProperties(), smsCodeLogRepository, smsRiskControlService);
        SmsCodeLogEntity latestLog = buildActiveLog();
        latestLog.setOutId("latest-out-id");
        when(smsCodeLogRepository.findByOutId("stale-out-id")).thenReturn(Optional.empty());
        when(smsCodeLogRepository.findRecentUnconsumed("13800138000", "REGISTER", 5)).thenReturn(java.util.List.of(latestLog));

        CheckSmsVerifyCodeResponse response = new CheckSmsVerifyCodeResponse();
        response.setSuccess(true);
        CheckSmsVerifyCodeResponse.Model model = new CheckSmsVerifyCodeResponse.Model();
        model.setVerifyResult("PASS");
        response.setModel(model);

        try (MockedConstruction<DefaultAcsClient> mocked = mockConstruction(DefaultAcsClient.class, (mock, context) -> {
            when(mock.getAcsResponse(any(CheckSmsVerifyCodeRequest.class))).thenReturn(response);
        })) {
            service.verifyCode("13800138000", "REGISTER", "09fhds", "stale-out-id", true);

            verify(smsCodeLogRepository).findByOutId("stale-out-id");
            verify(smsCodeLogRepository).findRecentUnconsumed("13800138000", "REGISTER", 5);
            verify(smsCodeLogRepository).markVerified("stale-out-id", "PASS");
            verify(smsCodeLogRepository).markConsumed("stale-out-id");
            ArgumentCaptor<CheckSmsVerifyCodeRequest> requestCaptor = ArgumentCaptor.forClass(CheckSmsVerifyCodeRequest.class);
            verify(mocked.constructed().get(0)).getAcsResponse(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getOutId()).isEqualTo("stale-out-id");
            assertThat(mocked.constructed()).hasSize(1);
        }
    }

    @Test
    void shouldAllowRetryUsingVerifyFailedSmsRecord() throws Exception {
        SmsAuthService service = new SmsAuthService(buildProperties(), smsCodeLogRepository, smsRiskControlService);
        SmsCodeLogEntity failedLog = buildActiveLog();
        failedLog.setStatus("VERIFY_FAILED");
        when(smsCodeLogRepository.findByOutId("out-id-001")).thenReturn(Optional.of(failedLog));

        CheckSmsVerifyCodeResponse response = new CheckSmsVerifyCodeResponse();
        response.setSuccess(true);
        CheckSmsVerifyCodeResponse.Model model = new CheckSmsVerifyCodeResponse.Model();
        model.setVerifyResult("PASS");
        response.setModel(model);

        try (MockedConstruction<DefaultAcsClient> mocked = mockConstruction(DefaultAcsClient.class, (mock, context) -> {
            when(mock.getAcsResponse(any(CheckSmsVerifyCodeRequest.class))).thenReturn(response);
        })) {
            service.verifyCode("13800138000", "REGISTER", "09fhds", "out-id-001", true);

            verify(smsCodeLogRepository).markVerified("out-id-001", "PASS");
            verify(smsCodeLogRepository).markConsumed("out-id-001");
            assertThat(mocked.constructed()).hasSize(1);
        }
    }

    @Test
    void shouldRejectEmptySmsCodeBeforeCallingAliyun() {
        SmsAuthService service = new SmsAuthService(buildProperties(), smsCodeLogRepository, smsRiskControlService);

        assertThatThrownBy(() -> service.verifyCode("13800138000", "REGISTER", "   ", "out-id-001", true))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException businessException = (BusinessException) ex;
                assertThat(businessException.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST);
            });
    }

    @Test
    void shouldConvertConfiguredValidTimeMinutesToAliyunSeconds() throws Exception {
        SmsAuthProperties properties = buildProperties();
        properties.setValidTimeMinutes(5L);
        SmsAuthService service = new SmsAuthService(properties, smsCodeLogRepository, smsRiskControlService);

        SendSmsVerifyCodeResponse response = new SendSmsVerifyCodeResponse();
        response.setSuccess(true);
        SendSmsVerifyCodeResponse.Model model = new SendSmsVerifyCodeResponse.Model();
        model.setVerifyCode("gtwai9");
        response.setModel(model);

        try (MockedConstruction<DefaultAcsClient> mocked = mockConstruction(DefaultAcsClient.class, (mock, context) -> {
            when(mock.getAcsResponse(any(SendSmsVerifyCodeRequest.class))).thenReturn(response);
        })) {
            service.sendVerifyCode("13800138000", "REGISTER", "127.0.0.1");

            ArgumentCaptor<SendSmsVerifyCodeRequest> requestCaptor = ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
            verify(mocked.constructed().get(0)).getAcsResponse(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getValidTime()).isEqualTo(300L);
        }
    }

    private SmsAuthProperties buildProperties() {
        SmsAuthProperties properties = new SmsAuthProperties();
        properties.setEnabled(true);
        properties.setAccessKeyId("test-access-key");
        properties.setAccessKeySecret("test-access-secret");
        properties.setSignName("test-sign");
        properties.setSchemeName("noval-web");
        properties.setCountryCode("86");
        return properties;
    }

    private SmsCodeLogEntity buildActiveLog() {
        SmsCodeLogEntity entity = new SmsCodeLogEntity();
        entity.setPhone("13800138000");
        entity.setBizType("REGISTER");
        entity.setOutId("out-id-001");
        entity.setExpireTime(LocalDateTime.now().plusMinutes(5));
        entity.setStatus("SENT");
        return entity;
    }
}

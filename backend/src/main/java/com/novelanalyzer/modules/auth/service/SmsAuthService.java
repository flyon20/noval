package com.novelanalyzer.modules.auth.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dypnsapi.model.v20170525.CheckSmsVerifyCodeRequest;
import com.aliyuncs.dypnsapi.model.v20170525.CheckSmsVerifyCodeResponse;
import com.aliyuncs.dypnsapi.model.v20170525.SendSmsVerifyCodeRequest;
import com.aliyuncs.dypnsapi.model.v20170525.SendSmsVerifyCodeResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.SmsAuthProperties;
import com.novelanalyzer.modules.auth.model.SmsCodeLogEntity;
import com.novelanalyzer.modules.auth.repository.SmsCodeLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class SmsAuthService {

    private static final String PROVIDER = "aliyun-pnvs";
    private static final Logger LOGGER = LoggerFactory.getLogger(SmsAuthService.class);

    private final SmsAuthProperties smsAuthProperties;
    private final SmsCodeLogRepository smsCodeLogRepository;
    private final SmsRiskControlService smsRiskControlService;

    public SmsAuthService(SmsAuthProperties smsAuthProperties,
                          SmsCodeLogRepository smsCodeLogRepository,
                          SmsRiskControlService smsRiskControlService) {
        this.smsAuthProperties = smsAuthProperties;
        this.smsCodeLogRepository = smsCodeLogRepository;
        this.smsRiskControlService = smsRiskControlService;
    }

    public SendResult sendVerifyCode(String phone, String bizType, String sendIp) {
        assertSmsConfigured();
        smsRiskControlService.assertCanSend(phone, bizType, sendIp, smsAuthProperties.getIntervalSeconds());

        String outId = UUID.randomUUID().toString().replace("-", "");
        try {
            IAcsClient client = buildClient();
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest();
            request.setCountryCode(smsAuthProperties.getCountryCode());
            request.setPhoneNumber(phone);
            request.setSignName(smsAuthProperties.getSignName());
            request.setSchemeName(smsAuthProperties.getSchemeName());
            request.setTemplateCode(resolveTemplateCode(bizType));
            request.setTemplateParam("{\"code\":\"##code##\",\"min\":\"" + smsAuthProperties.getValidTimeMinutes() + "\"}");
            request.setValidTime(smsAuthProperties.getValidTimeMinutes() * 60L);
            request.setInterval(smsAuthProperties.getIntervalSeconds());
            request.setCodeLength(6L);
            request.setCodeType(6L);
            request.setDuplicatePolicy(1L);
            request.setReturnVerifyCode(true);
            request.setOutId(outId);

            SendSmsVerifyCodeResponse response = client.getAcsResponse(request);
            if (!Boolean.TRUE.equals(response.getSuccess())) {
                LOGGER.warn(
                    "aliyun sms send failed phone={} bizType={} code={} message={} requestId={} bizId={} outId={}",
                    phone,
                    bizType,
                    response.getCode(),
                    response.getMessage(),
                    response.getModel() == null ? null : response.getModel().getRequestId(),
                    response.getModel() == null ? null : response.getModel().getBizId(),
                    outId
                );
                saveFailedSend(phone, bizType, sendIp, outId, response.getMessage());
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "验证码发送失败，请稍后重试");
            }

            SmsCodeLogEntity entity = new SmsCodeLogEntity();
            entity.setPhone(phone);
            entity.setBizType(normalizeBizType(bizType));
            entity.setProvider(PROVIDER);
            entity.setOutId(outId);
            entity.setRequestId(response.getModel() == null ? null : response.getModel().getRequestId());
            entity.setBizId(response.getModel() == null ? null : response.getModel().getBizId());
            entity.setSchemeName(smsAuthProperties.getSchemeName());
            entity.setStatus("SENT");
            entity.setSendIp(sendIp);
            entity.setTraceId(TraceIdHolder.get());
            entity.setMessage(response.getMessage());
            entity.setExpireTime(LocalDateTime.now().plusMinutes(smsAuthProperties.getValidTimeMinutes()));
            smsCodeLogRepository.insert(entity);
            LOGGER.info(
                "aliyun sms send success phone={} bizType={} outId={} requestId={} bizId={} verifyCode={}",
                phone,
                bizType,
                outId,
                response.getModel() == null ? null : response.getModel().getRequestId(),
                response.getModel() == null ? null : response.getModel().getBizId(),
                response.getModel() == null ? null : response.getModel().getVerifyCode()
            );
            return new SendResult(
                response.getModel() == null ? null : response.getModel().getVerifyCode(),
                outId
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (ClientException ex) {
            LOGGER.warn(
                "aliyun sms send client exception phone={} bizType={} outId={} errCode={} errMsg={}",
                phone,
                bizType,
                outId,
                ex.getErrCode(),
                ex.getErrMsg()
            );
            saveFailedSend(phone, bizType, sendIp, outId, ex.getErrMsg());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "验证码发送失败，请稍后重试");
        }
    }

    public void verifyCode(String phone, String bizType, String smsCode, String smsOutId, boolean consume) {
        assertSmsConfigured();
        String normalizedSmsCode = normalizeVerifyCode(smsCode);
        SmsCodeLogEntity log = resolveSmsLog(phone, bizType, smsOutId).orElse(null);
        String verifyOutId = resolveVerifyOutId(log, smsOutId);
        if (verifyOutId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码不存在或已过期");
        }

        if (log != null && log.getExpireTime() != null && log.getExpireTime().isBefore(LocalDateTime.now())) {
            smsCodeLogRepository.markFailed(log.getOutId(), "EXPIRED", "expired");
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码不存在或已过期");
        }

        try {
            IAcsClient client = buildClient();
            CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest();
            request.setCountryCode(smsAuthProperties.getCountryCode());
            request.setPhoneNumber(phone);
            request.setVerifyCode(normalizedSmsCode);
            request.setSchemeName(smsAuthProperties.getSchemeName());
            request.setOutId(verifyOutId);
            request.setCaseAuthPolicy(1L);

            CheckSmsVerifyCodeResponse response = client.getAcsResponse(request);
            String verifyResult = response.getModel() == null ? null : response.getModel().getVerifyResult();
            if (!Boolean.TRUE.equals(response.getSuccess()) || !isVerifyPassed(verifyResult)) {
                LOGGER.warn(
                    "aliyun sms verify failed phone={} bizType={} code={} message={} verifyResult={} outId={}",
                    phone,
                    bizType,
                    response.getCode(),
                    response.getMessage(),
                    verifyResult,
                    verifyOutId
                );
                smsCodeLogRepository.markFailed(verifyOutId, "VERIFY_FAILED", firstNonBlank(response.getMessage(), verifyResult, "verify failed"));
                throw new BusinessException(ResultCode.BAD_REQUEST, "验证码错误或已失效");
            }

            smsCodeLogRepository.markVerified(verifyOutId, verifyResult);
            if (consume) {
                smsCodeLogRepository.markConsumed(verifyOutId);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (ClientException ex) {
            LOGGER.warn(
                "aliyun sms verify client exception phone={} bizType={} outId={} errCode={} errMsg={}",
                phone,
                bizType,
                verifyOutId,
                ex.getErrCode(),
                ex.getErrMsg()
            );
            if (isVerifyRejected(ex)) {
                smsCodeLogRepository.markFailed(verifyOutId, "VERIFY_FAILED", firstNonBlank(ex.getErrMsg(), ex.getErrCode(), "verify failed"));
                throw new BusinessException(ResultCode.BAD_REQUEST, "\u9a8c\u8bc1\u7801\u9519\u8bef\u6216\u5df2\u5931\u6548");
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "\u9a8c\u8bc1\u7801\u6821\u9a8c\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
    }

    private java.util.Optional<SmsCodeLogEntity> resolveSmsLog(String phone, String bizType, String smsOutId) {
        if (!isBlank(smsOutId)) {
            java.util.Optional<SmsCodeLogEntity> exactMatch = smsCodeLogRepository.findByOutId(smsOutId.trim())
                .filter(item -> phone.equals(item.getPhone()))
                .filter(item -> normalizeBizType(bizType).equals(normalizeBizType(item.getBizType())))
                .filter(this::isUsableForVerification);
            if (exactMatch.isPresent()) {
                return exactMatch;
            }
        }
        return smsCodeLogRepository.findRecentUnconsumed(phone, normalizeBizType(bizType), 5)
            .stream()
            .filter(this::isUsableForVerification)
            .findFirst();
    }

    private String resolveVerifyOutId(SmsCodeLogEntity log, String smsOutId) {
        if (!isBlank(smsOutId)) {
            return smsOutId.trim();
        }
        if (log == null || isBlank(log.getOutId())) {
            return null;
        }
        return log.getOutId().trim();
    }

    private IAcsClient buildClient() {
        try {
            DefaultProfile profile = DefaultProfile.getProfile("", smsAuthProperties.getAccessKeyId(), smsAuthProperties.getAccessKeySecret());
            profile.addEndpoint("", "", "Dypnsapi", smsAuthProperties.getEndpoint());
            return new DefaultAcsClient(profile);
        } catch (ClientException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "短信认证客户端初始化失败");
        }
    }

    private void saveFailedSend(String phone, String bizType, String sendIp, String outId, String message) {
        SmsCodeLogEntity entity = new SmsCodeLogEntity();
        entity.setPhone(phone);
        entity.setBizType(normalizeBizType(bizType));
        entity.setProvider(PROVIDER);
        entity.setOutId(outId);
        entity.setSchemeName(smsAuthProperties.getSchemeName());
        entity.setStatus("SEND_FAILED");
        entity.setSendIp(sendIp);
        entity.setTraceId(TraceIdHolder.get());
        entity.setMessage(message);
        smsCodeLogRepository.insert(entity);
    }

    private void assertSmsConfigured() {
        if (!smsAuthProperties.isEnabled()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "短信认证未启用");
        }
        if (isBlank(smsAuthProperties.getAccessKeyId())
            || isBlank(smsAuthProperties.getAccessKeySecret())
            || isBlank(smsAuthProperties.getSignName())
            || isBlank(smsAuthProperties.getSchemeName())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "短信认证配置不完整");
        }
    }

    private String resolveTemplateCode(String bizType) {
        return switch (normalizeBizType(bizType)) {
            case "REGISTER" -> smsAuthProperties.getTemplateCodeRegister();
            case "LOGIN" -> smsAuthProperties.getTemplateCodeLogin();
            case "RESET_PASSWORD" -> smsAuthProperties.getTemplateCodeResetPassword();
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "bizType is invalid");
        };
    }

    private boolean isVerifyPassed(String verifyResult) {
        if (verifyResult == null || verifyResult.isBlank()) {
            return false;
        }
        String normalized = verifyResult.trim().toUpperCase(Locale.ROOT);
        return "PASS".equals(normalized) || "SUCCESS".equals(normalized) || "OK".equals(normalized);
    }

    private boolean isVerifyRejected(ClientException ex) {
        if (ex == null) {
            return false;
        }
        String errCode = ex.getErrCode();
        return errCode != null && "ISV.VALIDATEFAIL".equalsIgnoreCase(errCode.trim());
    }

    private String normalizeBizType(String bizType) {
        return bizType == null ? "" : bizType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeVerifyCode(String smsCode) {
        String normalized = smsCode == null ? "" : smsCode.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "\u9a8c\u8bc1\u7801\u4e0d\u5b58\u5728\u6216\u5df2\u8fc7\u671f");
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isUsableForVerification(SmsCodeLogEntity item) {
        if (item == null) {
            return false;
        }
        String status = item.getStatus() == null ? "" : item.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!"SENT".equals(status) && !"VERIFY_FAILED".equals(status)) {
            return false;
        }
        if (item.getConsumedTime() != null) {
            return false;
        }
        return item.getExpireTime() == null || item.getExpireTime().isAfter(LocalDateTime.now());
    }

    public record SendResult(String debugVerifyCode, String outId) {
    }
}

package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.CloudflareTurnstileProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Service
public class TurnstileService {

    private final CloudflareTurnstileProperties turnstileProperties;
    private final RestTemplate restTemplate;

    public TurnstileService(CloudflareTurnstileProperties turnstileProperties,
                            RestTemplateBuilder restTemplateBuilder) {
        this.turnstileProperties = turnstileProperties;
        this.restTemplate = restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(5))
            .build();
    }

    public boolean isEnabled() {
        return turnstileProperties.isEnabled();
    }

    public String getSiteKey() {
        return blankToNull(turnstileProperties.getSiteKey());
    }

    public void assertSmsSendPassed(String token, String remoteIp) {
        if (!isEnabled()) {
            return;
        }
        if (blankToNull(token) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请完成人机校验后再发送验证码");
        }
        if (blankToNull(turnstileProperties.getSecretKey()) == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "人机校验配置不完整");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", turnstileProperties.getSecretKey());
        form.add("response", token.trim());
        if (blankToNull(remoteIp) != null) {
            form.add("remoteip", remoteIp.trim());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<Map> response = restTemplate.postForEntity(
            turnstileProperties.getVerifyUrl(),
            new HttpEntity<>(form, headers),
            Map.class
        );
        Map<?, ?> body = response.getBody();
        boolean success = body != null && Boolean.TRUE.equals(body.get("success"));
        if (!success) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请完成人机校验后再发送验证码");
        }

        String expectedHostname = blankToNull(turnstileProperties.getExpectedHostname());
        if (expectedHostname != null) {
            String hostname = body == null ? null : asString(body.get("hostname"));
            if (!expectedHostname.equalsIgnoreCase(blankToNull(hostname))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "人机校验结果无效");
            }
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

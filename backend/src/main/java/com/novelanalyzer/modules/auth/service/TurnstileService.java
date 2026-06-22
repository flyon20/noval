package com.novelanalyzer.modules.auth.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.CloudflareTurnstileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Service
public class TurnstileService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TurnstileService.class);
    private static final String TURNSTILE_REQUIRED_MESSAGE = "请完成人机校验后再发送验证码";
    private static final String TURNSTILE_INVALID_MESSAGE = "人机校验结果无效";

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
            throw new BusinessException(ResultCode.BAD_REQUEST, TURNSTILE_REQUIRED_MESSAGE);
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

        ResponseEntity<Map> response;
        try {
            response = restTemplate.postForEntity(
                turnstileProperties.getVerifyUrl(),
                new HttpEntity<>(form, headers),
                Map.class
            );
        } catch (RestClientException ex) {
            LOGGER.warn(
                "cloudflare turnstile siteverify request failed remoteIp={} exceptionType={} message={}",
                remoteIp,
                ex.getClass().getSimpleName(),
                ex.getMessage()
            );
            throw new BusinessException(ResultCode.BAD_REQUEST, TURNSTILE_REQUIRED_MESSAGE);
        }

        Map<?, ?> body = response.getBody();
        boolean success = body != null && Boolean.TRUE.equals(body.get("success"));
        if (!success) {
            LOGGER.warn(
                "cloudflare turnstile verification failed errorCodes={} hostname={} action={} remoteIp={}",
                body == null ? null : body.get("error-codes"),
                body == null ? null : body.get("hostname"),
                body == null ? null : body.get("action"),
                remoteIp
            );
            throw new BusinessException(ResultCode.BAD_REQUEST, TURNSTILE_REQUIRED_MESSAGE);
        }

        String expectedHostname = blankToNull(turnstileProperties.getExpectedHostname());
        if (expectedHostname != null) {
            String hostname = body == null ? null : asString(body.get("hostname"));
            if (!expectedHostname.equalsIgnoreCase(blankToNull(hostname))) {
                LOGGER.warn(
                    "cloudflare turnstile hostname mismatch expectedHostname={} actualHostname={} remoteIp={}",
                    expectedHostname,
                    hostname,
                    remoteIp
                );
                throw new BusinessException(ResultCode.BAD_REQUEST, TURNSTILE_INVALID_MESSAGE);
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

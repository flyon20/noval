package com.novelanalyzer.modules.security.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class InternalServiceAuthService {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Service-Token";

    private final AiProperties aiProperties;
    private final SystemConfigService systemConfigService;

    public InternalServiceAuthService(AiProperties aiProperties,
                                      SystemConfigService systemConfigService) {
        this.aiProperties = aiProperties;
        this.systemConfigService = systemConfigService;
    }

    public void assertLangGraphWorkerCaller(HttpServletRequest request) {
        String expected = resolveLangGraphWorkerInternalApiKey();
        String actual = request == null ? null : request.getHeader(INTERNAL_API_KEY_HEADER);
        if (expected == null || expected.isBlank() || actual == null || !expected.equals(actual)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "invalid internal service token");
        }
    }

    private String resolveLangGraphWorkerInternalApiKey() {
        String value = systemConfigService.getValueOrDefault("ai.langgraph-worker.internal-api-key", null);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        String configured = aiProperties.getLanggraphWorker().getInternalApiKey();
        return configured == null ? "" : configured.trim();
    }
}

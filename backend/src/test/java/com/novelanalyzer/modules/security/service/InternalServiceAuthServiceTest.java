package com.novelanalyzer.modules.security.service;

import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalServiceAuthServiceTest {

    @Test
    void shouldAllowEnvInternalTokenWhenDatabaseConfigIsStale() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getLanggraphWorker().setInternalApiKey("env-worker-token");
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getValueOrDefault("ai.langgraph-worker.internal-api-key", null))
            .thenReturn("stale-db-token");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Internal-Service-Token")).thenReturn("env-worker-token");
        InternalServiceAuthService service = new InternalServiceAuthService(aiProperties, systemConfigService);

        service.assertLangGraphWorkerCaller(request);
    }
}

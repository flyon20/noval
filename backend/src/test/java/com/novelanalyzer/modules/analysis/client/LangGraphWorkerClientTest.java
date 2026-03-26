package com.novelanalyzer.modules.analysis.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangGraphWorkerClientTest {

    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final LangGraphWorkerClient client = new LangGraphWorkerClient(
        mock(HttpClient.class),
        new ObjectMapper(),
        new AiProperties(),
        systemConfigService
    );

    @Test
    void shouldExtractReadableMessageFromWorkerJsonErrorBody() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
            client,
            "ensureSuccess",
            502,
            "{\"detail\":\"AI provider connection failed, please retry\"}"
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("AI provider connection failed, please retry");
    }

    @Test
    void shouldUseRequestTimeoutWhenItIsLongerThanWorkerDefault() {
        when(systemConfigService.getIntValueOrDefault("ai.langgraph-worker.timeout-millis", 30000))
            .thenReturn(30000);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeoutMillis", 180000);
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("limits", limits);

        Integer timeoutMillis = ReflectionTestUtils.invokeMethod(client, "resolveTimeoutMillis", requestPayload);

        assertThat(timeoutMillis).isEqualTo(190000);
    }
}

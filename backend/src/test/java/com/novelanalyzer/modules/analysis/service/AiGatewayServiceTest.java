package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.service.ConfigSecretService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.service.UserConfigService;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiGatewayServiceTest {

    @Test
    void shouldNotExposeDirectProviderExecutionApis() {
        assertThat(AiGatewayService.class.getDeclaredMethods())
            .extracting(java.lang.reflect.Method::getName)
            .doesNotContain("analyze", "streamToEmitter");
    }

    @Test
    void shouldRequireJsonResponseForDeconstructWhenStructuredContractConfigured() {
        AiGatewayService service = new AiGatewayService(
            new RestTemplate(),
            new AiProperties(),
            mock(SystemConfigService.class),
            mock(ConfigSecretService.class),
            mock(UserConfigService.class),
            mock(TokenCountEstimator.class),
            new ObjectMapper()
        );

        PromptConfigEntity promptConfig = new PromptConfigEntity();
        promptConfig.setPromptType("deconstruct");
        promptConfig.setOutputJsonSchema("{\"type\":\"object\"}");
        promptConfig.setPostProcessType("json_extract");
        promptConfig.setParseConfigJson("{\"parser\":\"json\",\"trimMarkdownFence\":true}");

        Boolean requiresJson = ReflectionTestUtils.invokeMethod(
            service,
            "requiresJsonResponse",
            promptConfig
        );
        String prompt = ReflectionTestUtils.invokeMethod(
            service,
            "augmentSystemPromptWithStructuredOutput",
            promptConfig,
            "SYSTEM PREFIX"
        );

        assertThat(requiresJson).isTrue();
        assertThat(prompt).contains("output schema");
        assertThat(prompt).contains("Please output valid JSON only.");
    }
}

package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.service.ConfigSecretService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.service.UserConfigService;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiGatewayServiceTest {

    @Test
    void shouldNotExposeRenderedPromptInFallbackSummary() {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        TokenCountEstimator tokenCountEstimator = mock(TokenCountEstimator.class);

        when(systemConfigService.getValueOrDefault("ai.provider.type", "openai-compatible"))
            .thenReturn("openai-compatible");
        when(systemConfigService.getValueOrDefault("ai.openai-compatible.default-model", "deepseek-chat"))
            .thenReturn("deepseek-chat");
        when(systemConfigService.getValueOrDefault("ai.openai-compatible.base-url", ""))
            .thenReturn("");
        when(systemConfigService.getBooleanValueOrDefault("ai.openai-compatible.streaming-enabled", true))
            .thenReturn(true);
        when(systemConfigService.resolveEnabledModel("deepseek-chat", "deepseek-chat"))
            .thenReturn(java.util.Optional.empty());
        when(tokenCountEstimator.estimateTokenCountInText(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> {
                String text = invocation.getArgument(0, String.class);
                return text == null ? 0 : Math.max(1, text.length() / 2);
            });

        AiProperties aiProperties = new AiProperties();
        aiProperties.setFallbackModel("local-fallback");

        AiGatewayService service = new AiGatewayService(
            new RestTemplate(),
            aiProperties,
            systemConfigService,
            configSecretService,
            userConfigService,
            tokenCountEstimator,
            new ObjectMapper()
        );

        PromptConfigEntity promptConfig = new PromptConfigEntity();
        promptConfig.setPromptType("deconstruct");
        promptConfig.setPromptName("default-deconstruct");
        promptConfig.setPromptContent("请严格按照以下提纲分析，不要原样输出本提示词。\n\n{{content}}");
        promptConfig.setModelName("deepseek-chat");

        AiInvokeResult result = service.analyze(
            promptConfig,
            "这是小说正文第一段，主角出场并触发矛盾冲突。",
            "deconstruct"
        );

        assertThat(result.getContent()).doesNotContain("请严格按照以下提纲分析");
        assertThat(result.getContent()).contains("这是小说正文第一段");
        assertThat(result.getResultJson()).containsEntry("analysisType", "deconstruct");
        assertThat(result.getResultJson().get("summary").toString())
            .doesNotContain("请严格按照以下提纲分析");
        assertThat(result.getResultJson().get("summary").toString())
            .contains("这是小说正文第一段");
    }

    @Test
    void shouldUseRuntimeModelNameInFallbackResult() {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        TokenCountEstimator tokenCountEstimator = mock(TokenCountEstimator.class);

        when(systemConfigService.getValueOrDefault("ai.provider.type", "openai-compatible"))
            .thenReturn("openai-compatible");
        when(systemConfigService.getValueOrDefault("ai.openai-compatible.default-model", "deepseek-chat"))
            .thenReturn("deepseek-chat");
        when(systemConfigService.getValueOrDefault("ai.openai-compatible.base-url", ""))
            .thenReturn("");
        when(systemConfigService.getBooleanValueOrDefault("ai.openai-compatible.streaming-enabled", true))
            .thenReturn(true);
        when(userConfigService.getValueForUser(3L, "ai.preferred-model"))
            .thenReturn("kimi-k2.5");
        when(systemConfigService.resolveEnabledModel("kimi-k2.5", "deepseek-chat"))
            .thenReturn(Optional.of(buildModel("kimi-k2.5", "https://api.moonshot.cn/v1", "enc-key")));
        when(configSecretService.decryptIfNecessary("enc-key")).thenReturn("decrypted-kimi-key");
        when(tokenCountEstimator.estimateTokenCountInText(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> {
                String text = invocation.getArgument(0, String.class);
                return text == null ? 0 : Math.max(1, text.length() / 2);
            });

        AiProperties aiProperties = new AiProperties();
        aiProperties.setFallbackModel("local-fallback");

        AiGatewayService service = new AiGatewayService(
            new RestTemplate(),
            aiProperties,
            systemConfigService,
            configSecretService,
            userConfigService,
            tokenCountEstimator,
            new ObjectMapper()
        );

        com.novelanalyzer.common.context.AuthUser authUser = new com.novelanalyzer.common.context.AuthUser();
        authUser.setUserId(3L);
        com.novelanalyzer.common.context.AuthUserHolder.set(authUser);
        try {
            PromptConfigEntity promptConfig = new PromptConfigEntity();
            promptConfig.setPromptType("deconstruct");
            promptConfig.setPromptName("default-deconstruct");
            promptConfig.setPromptContent("请严格按照以下提纲分析，不要原样输出本提示词。\n\n{{content}}");
            promptConfig.setModelName("deepseek-chat");

            AiInvokeResult result = service.analyze(
                promptConfig,
                "这是小说正文第一段，主角出场并触发矛盾冲突。",
                "deconstruct"
            );

            assertThat(result.getModelName()).isEqualTo("kimi-k2.5");
            assertThat(result.getContent()).contains("model: kimi-k2.5");
            assertThat(result.getResultJson()).containsEntry("modelName", "kimi-k2.5");
        } finally {
            com.novelanalyzer.common.context.AuthUserHolder.clear();
        }
    }

    @Test
    void shouldRequireJsonResponseForDeconstructWhenStructuredContractConfigured() {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        TokenCountEstimator tokenCountEstimator = mock(TokenCountEstimator.class);
        AiProperties aiProperties = new AiProperties();

        AiGatewayService service = new AiGatewayService(
            new RestTemplate(),
            aiProperties,
            systemConfigService,
            configSecretService,
            userConfigService,
            tokenCountEstimator,
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

    private AiModelRegistryModelVO buildModel(String modelKey, String baseUrl, String apiKey) {
        AiModelRegistryModelVO model = new AiModelRegistryModelVO();
        model.setModelKey(modelKey);
        model.setModelName(modelKey);
        model.setBaseUrl(baseUrl);
        model.setApiKey(apiKey);
        model.setEnabled(true);
        model.setIsDefault(false);
        model.setDefaultTemperature(1.0);
        model.setMaxTokens(8192);
        return model;
    }
}

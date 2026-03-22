package com.novelanalyzer.modules.analysis.service;

import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;

@Service
public class AiGatewayService {

    private static final Map<String, String> DEFAULT_PROMPT_TEMPLATES = Map.of(
        "deconstruct", "请基于以下小说正文进行拆文分析，重点输出：核心卖点、开篇钩子、人物关系、冲突设计、节奏爽点与可优化点。\n\n{{content}}",
        "structure", "请基于以下小说正文进行结构分析，重点关注开篇铺垫、冲突推进、转折设置、悬念设计与章节结构。\n\n{{content}}",
        "plot", "请基于以下小说正文进行情节分析，概括关键事件、人物动机、冲突升级与后续看点。\n\n{{content}}",
        "theme", "Please analyze the following trend data and summarize core themes, changes, and representative books.\n\n{{content}}"
    );

    private final RestTemplate aiRestTemplate;
    private final AiProperties aiProperties;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    public AiGatewayService(RestTemplate aiRestTemplate,
                            AiProperties aiProperties,
                            SystemConfigService systemConfigService,
                            ObjectMapper objectMapper) {
        this.aiRestTemplate = aiRestTemplate;
        this.aiProperties = aiProperties;
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
    }

    public AiInvokeResult analyze(PromptConfigEntity promptConfig, String text, String analysisType) {
        String renderedPrompt = renderPrompt(promptConfig.getPromptContent(), text, analysisType);
        AiInvokeResult providerResult = invokePreferredProvider(promptConfig, renderedPrompt, analysisType);
        if (providerResult != null) {
            return providerResult;
        }
        return buildFallbackResult(promptConfig, analysisType, renderedPrompt);
    }

    public int estimatePromptTokens(PromptConfigEntity promptConfig, String text, String analysisType) {
        String renderedPrompt = renderPrompt(promptConfig == null ? null : promptConfig.getPromptContent(), text, analysisType);
        return estimateTokenCount(renderedPrompt);
    }

    public String resolvePromptTemplate(PromptConfigEntity promptConfig, String analysisType) {
        return normalizePromptTemplate(promptConfig == null ? null : promptConfig.getPromptContent(), analysisType);
    }

    private String renderPrompt(String template, String text, String analysisType) {
        String safeTemplate = normalizePromptTemplate(template, analysisType);
        return PromptTemplate.from(safeTemplate)
            .apply(Map.of("content", text, "analysisType", analysisType))
            .text();
    }

    private String normalizePromptTemplate(String template, String analysisType) {
        if (template != null && !template.isBlank() && template.contains("{{content}}")) {
            return template;
        }
        return DEFAULT_PROMPT_TEMPLATES.getOrDefault(analysisType, "{{content}}");
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 2.0d));
    }

    private AiInvokeResult invokePreferredProvider(PromptConfigEntity promptConfig,
                                                   String renderedPrompt,
                                                   String analysisType) {
        String providerType = resolveProviderType();
        if ("dify".equalsIgnoreCase(providerType)) {
            AiInvokeResult difyResult = invokeDify(promptConfig, renderedPrompt, analysisType);
            if (difyResult != null) {
                return difyResult;
            }
            return invokeOpenAiCompatible(promptConfig, renderedPrompt, analysisType);
        }

        AiInvokeResult openAiResult = invokeOpenAiCompatible(promptConfig, renderedPrompt, analysisType);
        if (openAiResult != null) {
            return openAiResult;
        }
        return invokeDify(promptConfig, renderedPrompt, analysisType);
    }

    private AiInvokeResult invokeOpenAiCompatible(PromptConfigEntity promptConfig,
                                                  String renderedPrompt,
                                                  String analysisType) {
        String apiKey = resolveOpenAiCompatibleApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String modelName = resolveOpenAiCompatibleModelName(promptConfig);
        if (modelName == null || modelName.isBlank()) {
            return null;
        }

        try {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(resolveOpenAiCompatibleBaseUrl())
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMillis(resolveTimeoutMillis()))
                .logRequests(false)
                .logResponses(false);

            if (promptConfig.getTemperature() != null) {
                builder.temperature(promptConfig.getTemperature());
            }
            if (promptConfig.getMaxTokens() != null) {
                builder.maxTokens(promptConfig.getMaxTokens());
            }

            ChatRequest chatRequest = ChatRequest.builder()
                .messages(UserMessage.from(renderedPrompt))
                .build();
            ChatResponse chatResponse = builder.build().chat(chatRequest);
            if (chatResponse == null || chatResponse.aiMessage() == null) {
                return null;
            }

            String content = chatResponse.aiMessage().text();
            if (content == null || content.isBlank()) {
                return null;
            }

            String resolvedModelName = firstNonBlank(chatResponse.modelName(), modelName);
            Integer totalTokens = Optional.ofNullable(chatResponse.tokenUsage())
                .map(tokenUsage -> tokenUsage.totalTokenCount())
                .orElse(null);
            Map<String, Object> resultJson = buildStructuredResult(
                Map.of(),
                content,
                analysisType,
                resolvedModelName
            );

            return AiInvokeResult.of(
                resolvedModelName,
                content,
                totalTokens == null ? Math.max(120, renderedPrompt.length() / 2) : totalTokens,
                resultJson
            );
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private AiInvokeResult invokeDify(PromptConfigEntity promptConfig, String renderedPrompt, String analysisType) {
        String workflowId = promptConfig.getDifyWorkflowId();
        String apiKey = resolveDifyApiKey(promptConfig.getDifyApiKeyRef());
        if (workflowId == null || workflowId.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("inputs", Map.of(
                "content", renderedPrompt,
                "analysisType", analysisType,
                "workflowId", workflowId
            ));
            body.put("response_mode", "blocking");
            body.put("user", "novel-analyzer");

            ResponseEntity<Map> response = aiRestTemplate.postForEntity(
                aiProperties.getDifyBaseUrl() + "/workflows/run",
                new HttpEntity<>(body, headers),
                Map.class
            );
            Map<String, Object> payload = response.getBody();
            if (payload == null) {
                return null;
            }

            Map<String, Object> data = asMap(payload.get("data"));
            Map<String, Object> outputs = asMap(data.get("outputs"));
            String content = firstNonBlank(
                asString(outputs.get("text")),
                asString(outputs.get("result")),
                asString(outputs.get("answer")),
                asString(data.get("answer"))
            );
            if (content == null || content.isBlank()) {
                return null;
            }
            Map<String, Object> resultJson = buildStructuredResult(outputs, content, analysisType, "dify:" + workflowId);

            Integer tokenUsed = Optional.ofNullable(data.get("total_tokens"))
                .map(Object::toString)
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                })
                .orElse(Math.max(120, renderedPrompt.length() / 2));

            return AiInvokeResult.of(
                "dify:" + workflowId,
                content,
                tokenUsed,
                resultJson
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private AiInvokeResult buildFallbackResult(PromptConfigEntity promptConfig, String analysisType, String renderedPrompt) {
        String modelName = promptConfig.getModelName() == null || promptConfig.getModelName().isBlank()
            ? aiProperties.getFallbackModel()
            : promptConfig.getModelName();
        String content = analysisType + " analysis result\n"
            + "model: " + modelName + "\n"
            + "summary: " + shortText(renderedPrompt, 400);
        Map<String, Object> resultJson = Map.of(
            "analysisType", analysisType,
            "modelName", modelName,
            "summary", shortText(renderedPrompt, 200),
            "content", content
        );
        int tokenUsed = Math.max(120, renderedPrompt.length() / 2);
        return AiInvokeResult.of(modelName, content, tokenUsed, resultJson);
    }

    private String resolveDifyApiKey(String keyRef) {
        if (keyRef == null || keyRef.isBlank()) {
            String globalKeyRef = aiProperties.getDifyApiKeyRef();
            if (globalKeyRef == null || globalKeyRef.isBlank()) {
                return resolveSecretValue("DIFY_API_KEY");
            }
            return resolveSecretValue(globalKeyRef);
        }
        return resolveSecretValue(keyRef);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String shortText(String input, int max) {
        if (input == null) {
            return "";
        }
        String clean = input.replaceAll("\\s+", " ").trim();
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(0, max) + "...";
    }

    private String resolveProviderType() {
        return systemConfigService.getValueOrDefault("ai.provider.type", aiProperties.getProviderType());
    }

    private int resolveTimeoutMillis() {
        return systemConfigService.getIntValueOrDefault("ai.timeout.millis", aiProperties.getTimeoutMillis());
    }

    private String resolveOpenAiCompatibleBaseUrl() {
        return systemConfigService.getValueOrDefault(
            "ai.openai-compatible.base-url",
            aiProperties.getOpenAiCompatible().getBaseUrl()
        );
    }

    private String resolveOpenAiCompatibleApiKey() {
        return resolveSecretValue(aiProperties.getOpenAiCompatible().getApiKeyRef());
    }

    private String resolveOpenAiCompatibleModelName(PromptConfigEntity promptConfig) {
        String configuredModel = promptConfig.getModelName();
        if (configuredModel == null || configuredModel.isBlank() || "dify".equalsIgnoreCase(configuredModel)) {
            return systemConfigService.getValueOrDefault(
                "ai.openai-compatible.default-model",
                aiProperties.getOpenAiCompatible().getDefaultModel()
            );
        }
        return configuredModel;
    }

    private String resolveSecretValue(String refName) {
        if (refName == null || refName.isBlank()) {
            return null;
        }
        String envValue = System.getenv(refName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        String propertyValue = System.getProperty(refName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        return null;
    }

    private Map<String, Object> buildStructuredResult(Map<String, Object> outputs,
                                                      String content,
                                                      String analysisType,
                                                      String modelName) {
        if (!outputs.isEmpty()) {
            Map<String, Object> normalized = new HashMap<>(outputs);
            normalized.putIfAbsent("analysisType", analysisType);
            normalized.putIfAbsent("modelName", modelName);
            normalized.putIfAbsent("summary", shortText(content, 200));
            return normalized;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(extractJsonObject(content), new TypeReference<Map<String, Object>>() {
            });
            parsed.putIfAbsent("analysisType", analysisType);
            parsed.putIfAbsent("modelName", modelName);
            return parsed;
        } catch (Exception ex) {
            return Map.of(
                "analysisType", analysisType,
                "modelName", modelName,
                "summary", shortText(content, 200),
                "content", content
            );
        }
    }

    private String extractJsonObject(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                trimmed = trimmed.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }
}

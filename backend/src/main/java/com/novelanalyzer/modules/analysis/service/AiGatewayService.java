package com.novelanalyzer.modules.analysis.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.service.UserConfigService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
    private final UserConfigService userConfigService;
    private final TokenCountEstimator tokenCountEstimator;
    private final ObjectMapper objectMapper;

    // 模型实例缓存：key = baseUrl|modelName|timeoutMillis，避免每次请求重建
    private final ConcurrentHashMap<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OpenAiStreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();
    private volatile String cachedApiKey = null;

    public AiGatewayService(RestTemplate aiRestTemplate,
                            AiProperties aiProperties,
                            SystemConfigService systemConfigService,
                            UserConfigService userConfigService,
                            TokenCountEstimator tokenCountEstimator,
                            ObjectMapper objectMapper) {
        this.aiRestTemplate = aiRestTemplate;
        this.aiProperties = aiProperties;
        this.systemConfigService = systemConfigService;
        this.userConfigService = userConfigService;
        this.tokenCountEstimator = tokenCountEstimator;
        this.objectMapper = objectMapper;
    }

    public AiInvokeResult analyze(PromptConfigEntity promptConfig, String text, String analysisType) {
        String renderedPrompt = renderPrompt(promptConfig.getPromptContent(), text, analysisType);
        AiInvokeResult providerResult = invokePreferredProvider(promptConfig, text, renderedPrompt, analysisType);
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
        return estimateTokenCountInternal(text);
    }

    private int estimateTokenCountInternal(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, tokenCountEstimator.estimateTokenCountInText(text));
    }

    private AiInvokeResult invokePreferredProvider(PromptConfigEntity promptConfig,
                                                   String text,
                                                   String renderedPrompt,
                                                   String analysisType) {
        String providerType = resolveProviderType();
        if ("dify".equalsIgnoreCase(providerType)) {
            AiInvokeResult difyResult = invokeDify(promptConfig, renderedPrompt, analysisType);
            if (difyResult != null) {
                return difyResult;
            }
            return invokeOpenAiCompatible(promptConfig, text, analysisType);
        }

        AiInvokeResult openAiResult = invokeOpenAiCompatible(promptConfig, text, analysisType);
        if (openAiResult != null) {
            return openAiResult;
        }
        return invokeDify(promptConfig, renderedPrompt, analysisType);
    }

    private AiInvokeResult invokeOpenAiCompatible(PromptConfigEntity promptConfig,
                                                  String text,
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
            PromptMessagePair promptMessages = buildPromptMessagePair(promptConfig, text, analysisType);
            OpenAiChatModel model = getOrCreateChatModel(
                resolveOpenAiCompatibleBaseUrl(), apiKey, modelName,
                promptConfig.getTemperature(), promptConfig.getMaxTokens()
            );
            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                .messages(promptMessages.messages());
            ResponseFormat responseFormat = resolveResponseFormat(promptConfig);
            if (responseFormat != null) {
                requestBuilder.responseFormat(responseFormat);
            }
            ChatResponse response = model.chat(requestBuilder.build());
            String content = response.aiMessage().text();
            if (content == null || content.isBlank()) {
                return null;
            }
            Integer totalTokens = Optional.ofNullable(response.tokenUsage())
                .map(u -> u.totalTokenCount())
                .orElse(null);
            Map<String, Object> resultJson = buildStructuredResult(promptConfig, Map.of(), content, analysisType, modelName);
            return AiInvokeResult.of(
                modelName,
                content,
                totalTokens != null ? totalTokens
                    : Math.max(120, estimateTokenCountInternal(promptMessages.combinedText()) + estimateTokenCountInternal(content)),
                resultJson
            );
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 真流式调用：onPartialResponse 直接推 SSE delta token，避免等全文再切割的假流式。
     * chunk 模式和 Dify 路径不走此方法，调用方需自行降级。
     *
     * @return true 表示已成功发起流式（异步完成），false 表示不支持流式需降级
     */
    public boolean streamToEmitter(PromptConfigEntity promptConfig,
                                   String text,
                                   String analysisType,
                                   SseEmitter emitter,
                                   BiConsumer<SseEmitter, AiInvokeResult> onDone,
                                   Consumer<Throwable> onError) {
        if (!isOpenAiCompatibleStreamingEnabled()) {
            return false;
        }
        String apiKey = resolveOpenAiCompatibleApiKey();
        String modelName = resolveOpenAiCompatibleModelName(promptConfig);
        if (apiKey == null || apiKey.isBlank() || modelName == null || modelName.isBlank()) {
            return false;
        }

        PromptMessagePair promptMessages = buildPromptMessagePair(promptConfig, text, analysisType);
        OpenAiStreamingChatModel streamModel = getOrCreateStreamingChatModel(
            resolveOpenAiCompatibleBaseUrl(), apiKey, modelName,
            promptConfig.getTemperature(), promptConfig.getMaxTokens()
        );

        StringBuilder buffer = new StringBuilder();
        final String resolvedModel = modelName;

        ChatRequest.Builder requestBuilder = ChatRequest.builder()
            .messages(promptMessages.messages());
        ResponseFormat responseFormat = resolveResponseFormat(promptConfig);
        if (responseFormat != null) {
            requestBuilder.responseFormat(responseFormat);
        }

        streamModel.doChat(requestBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                buffer.append(token);
                try {
                    emitter.send(SseEmitter.event()
                        .name("delta")
                        .data(Map.of("event", "delta", "delta", token)));
                } catch (IOException ignored) {
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (buffer.isEmpty()) {
                    emitRawStreamingDeltas(response, buffer, emitter);
                }
                String content = buffer.toString();
                String usedModel = firstNonBlank(response.modelName(), resolvedModel);
                Integer totalTokens = Optional.ofNullable(response.tokenUsage())
                    .map(u -> u.totalTokenCount()).orElse(null);
                Map<String, Object> resultJson = buildStructuredResult(promptConfig, Map.of(), content, analysisType, usedModel);
                AiInvokeResult result = AiInvokeResult.of(
                    usedModel, content,
                    totalTokens != null ? totalTokens
                        : Math.max(120, estimateTokenCountInternal(promptMessages.combinedText()) + estimateTokenCountInternal(content)),
                    resultJson
                );
                onDone.accept(emitter, result);
            }

            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }
        });
        return true;
    }

    private PromptMessagePair buildPromptMessagePair(PromptConfigEntity promptConfig,
                                                     String text,
                                                     String analysisType) {
        String template = normalizePromptTemplate(promptConfig == null ? null : promptConfig.getPromptContent(), analysisType);
        if (!template.contains("{{content}}")) {
            return new PromptMessagePair(
                List.of(UserMessage.from(renderPrompt(template, text, analysisType))),
                renderPrompt(template, text, analysisType)
            );
        }

        String systemPrompt = PromptTemplate.from(template)
            .apply(Map.of(
                "content", "正文内容会在下一条 user message 中提供，请只基于该正文完成分析。",
                "analysisType", analysisType
            ))
            .text();
        systemPrompt = augmentSystemPromptWithStructuredOutput(promptConfig, systemPrompt);
        return new PromptMessagePair(
            List.<ChatMessage>of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(text)
            ),
            systemPrompt + "\n" + text
        );
    }

    private String augmentSystemPromptWithStructuredOutput(PromptConfigEntity promptConfig, String systemPrompt) {
        if (!requiresJsonResponse(promptConfig)) {
            return systemPrompt;
        }
        StringBuilder builder = new StringBuilder(systemPrompt);
        builder.append("\n\nPlease output valid JSON only.");
        if (promptConfig != null && promptConfig.getOutputJsonSchema() != null && !promptConfig.getOutputJsonSchema().isBlank()) {
            builder.append("\noutput schema:\n").append(promptConfig.getOutputJsonSchema());
        }
        if (promptConfig != null && promptConfig.getOutputExampleJson() != null && !promptConfig.getOutputExampleJson().isBlank()) {
            builder.append("\noutput example:\n").append(promptConfig.getOutputExampleJson());
        }
        return builder.toString();
    }

    private ResponseFormat resolveResponseFormat(PromptConfigEntity promptConfig) {
        if (!requiresJsonResponse(promptConfig)) {
            return null;
        }
        return ResponseFormat.JSON;
    }

    private boolean requiresJsonResponse(PromptConfigEntity promptConfig) {
        if (promptConfig == null) {
            return false;
        }
        if (promptConfig.getOutputJsonSchema() != null && !promptConfig.getOutputJsonSchema().isBlank()) {
            return true;
        }
        if ("json_extract".equalsIgnoreCase(promptConfig.getPostProcessType())) {
            return true;
        }
        return promptConfig.getParseConfigJson() != null
            && promptConfig.getParseConfigJson().toLowerCase(java.util.Locale.ROOT).contains("\"parser\":\"json\"");
    }

    private void emitRawStreamingDeltas(ChatResponse response,
                                        StringBuilder buffer,
                                        SseEmitter emitter) {
        for (String delta : extractRawStreamingDeltas(response)) {
            buffer.append(delta);
            try {
                emitter.send(SseEmitter.event()
                    .name("delta")
                    .data(Map.of("event", "delta", "delta", delta)));
            } catch (IOException ignored) {
            }
        }
    }

    private java.util.List<String> extractRawStreamingDeltas(ChatResponse response) {
        if (!(response.metadata() instanceof OpenAiChatResponseMetadata metadata)) {
            return java.util.List.of();
        }
        if (metadata.rawServerSentEvents() == null || metadata.rawServerSentEvents().isEmpty()) {
            return java.util.List.of();
        }

        java.util.List<String> deltas = new java.util.ArrayList<>();
        for (dev.langchain4j.http.client.sse.ServerSentEvent event : metadata.rawServerSentEvents()) {
            String rawData = event == null ? null : event.data();
            if (rawData == null || rawData.isBlank() || "[DONE]".equals(rawData)) {
                continue;
            }
            try {
                Map<String, Object> payload = objectMapper.readValue(rawData, new TypeReference<Map<String, Object>>() {
                });
                java.util.List<Map<String, Object>> choices = objectMapper.convertValue(
                    payload.get("choices"),
                    new TypeReference<java.util.List<Map<String, Object>>>() {
                    }
                );
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                Map<String, Object> delta = objectMapper.convertValue(
                    choices.get(0).get("delta"),
                    new TypeReference<Map<String, Object>>() {
                    }
                );
                String content = asString(delta.get("content"));
                if (content != null && !content.isBlank()) {
                    deltas.add(content);
                }
            } catch (Exception ignored) {
            }
        }
        return deltas;
    }

    private boolean isOpenAiCompatibleStreamingEnabled() {
        return systemConfigService.getBooleanValueOrDefault(
            "ai.openai-compatible.streaming-enabled",
            aiProperties.getOpenAiCompatible().isStreamingEnabled()
        );
    }

    private OpenAiChatModel getOrCreateChatModel(String baseUrl, String apiKey,
                                                  String modelName,
                                                  Double temperature, Integer maxTokens) {
        String key = baseUrl + "|" + modelName + "|" + resolveTimeoutMillis()
            + "|" + temperature + "|" + maxTokens;
        // apiKey 变化时清空缓存
        if (!apiKey.equals(cachedApiKey)) {
            cachedApiKey = apiKey;
            chatModelCache.clear();
            streamingModelCache.clear();
        }
        return chatModelCache.computeIfAbsent(key, k -> {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMillis(resolveTimeoutMillis()))
                .logRequests(false)
                .logResponses(false);
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null) builder.maxTokens(maxTokens);
            return builder.build();
        });
    }

    private OpenAiStreamingChatModel getOrCreateStreamingChatModel(String baseUrl, String apiKey,
                                                                    String modelName,
                                                                    Double temperature, Integer maxTokens) {
        String key = baseUrl + "|" + modelName + "|" + resolveTimeoutMillis()
            + "|" + temperature + "|" + maxTokens;
        return streamingModelCache.computeIfAbsent(key, k -> {
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMillis(resolveTimeoutMillis()))
                .logRequests(false)
                .logResponses(false);
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null) builder.maxTokens(maxTokens);
            return builder.build();
        });
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
            Map<String, Object> resultJson = buildStructuredResult(promptConfig, outputs, content, analysisType, "dify:" + workflowId);

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
        // 1. prompt-level model (skip dify)
        String configuredModel = promptConfig.getModelName();
        if (configuredModel != null && !configuredModel.isBlank() && !"dify".equalsIgnoreCase(configuredModel)) {
            return configuredModel;
        }
        // 2. user preference
        try {
            com.novelanalyzer.common.context.AuthUser authUser = AuthUserHolder.get();
            if (authUser != null) {
                String userModel = userConfigService.getValueForUser(authUser.getUserId(), "ai.preferred-model");
                if (userModel != null && !userModel.isBlank()) {
                    return userModel;
                }
            }
        } catch (Exception ignored) {
        }
        // 3. system default
        return systemConfigService.getValueOrDefault(
            "ai.openai-compatible.default-model",
            aiProperties.getOpenAiCompatible().getDefaultModel()
        );
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

    private Map<String, Object> buildStructuredResult(PromptConfigEntity promptConfig,
                                                      Map<String, Object> outputs,
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
            Map<String, Object> parsed = parseStructuredResult(promptConfig, content);
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

    private Map<String, Object> parseStructuredResult(PromptConfigEntity promptConfig, String content) throws IOException {
        String normalized = content == null ? "" : content.trim();
        Map<String, Object> parseConfig = Map.of();
        if (promptConfig != null && promptConfig.getParseConfigJson() != null && !promptConfig.getParseConfigJson().isBlank()) {
            parseConfig = objectMapper.readValue(promptConfig.getParseConfigJson(), new TypeReference<Map<String, Object>>() {
            });
        }
        boolean trimMarkdownFence = Boolean.parseBoolean(String.valueOf(parseConfig.getOrDefault("trimMarkdownFence", false)));
        if (trimMarkdownFence || "json_extract".equalsIgnoreCase(promptConfig == null ? null : promptConfig.getPostProcessType())) {
            normalized = extractJsonObject(normalized);
        }
        return objectMapper.readValue(normalized, new TypeReference<Map<String, Object>>() {
        });
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

    private record PromptMessagePair(List<ChatMessage> messages, String combinedText) {
    }
}

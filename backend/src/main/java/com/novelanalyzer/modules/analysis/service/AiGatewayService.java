package com.novelanalyzer.modules.analysis.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
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
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final String THEME_STRUCTURED_GUIDANCE = """
        Trend output constraints:
        1. Never use broad labels such as 都市系统, 玄幻升级, 都市, or 系统流 as the final lane name.
        2. `themeDistribution.theme` and `themeTable.theme` must use 3 or 4 Chinese segments joined by '-' and must land on a concrete lane such as 都市脑洞-直播算命-惩恶扬善, 娱乐明星-老六系统-全网黑粉, 玄幻-长生苟道-家族养成, or 四合院-戾气极重-截胡流.
        3. Every `themeDistribution` and `themeTable` row must expose laneLevel, systemType, systemPresence, systemPersona, interactionMode, feedbackLoop, payoffMechanism, emotionAnchor, antiRoutineDesign, avoidedPoisonPoints, and microTags. If the story is not a classic system-flow, still explain the exact golden-finger form instead of writing a broad bucket.
        4. `systemArchetypes` must distinguish concrete system type, systemPresence, systemPersona, interaction mode, feedback loop, and payoff mechanism, for example 签到打卡流 / 神级选择流 / 情绪值收集流 / 熟练度面板 / 暴击返还流 / 听劝流.
        5. `microInnovationSignals` must explain the anti-cliche twist, antiRoutineDesign, avoidedPoisonPoints, and why the twist can work in the next 3-6 months.
        6. `historicalWordCloud` must contain concrete board-scoped terms: fine-grained lanes, system types, identity tags, emotion tags, scene tags, and micro-innovation words. Avoid umbrella words such as 系统流.
        7. `hotBooks` should be the highest-ranked representative title under each main lane, and each reason must explain why it represents that lane and which system/payoff loop makes it competitive.
        8. `insightCards` must at least cover 主赛道 and 代表热书 using the latest board-scoped sample, where 主赛道 is the lane with the highest ratio and 代表热书 is the highest-ranked title inside that lane.
        9. Keep `summary`, `boardSummary`, `trendPreview`, and `comparisonSummary` concise. `summary` should stay within 120 Chinese characters, `boardSummary` within 180 Chinese characters, `trendPreview` within 260 Chinese characters, and `comparisonSummary` within 180 Chinese characters.
        10. Keep `hotBooks`, `themeTable`, `themeDistribution`, `systemArchetypes`, `microInnovationSignals`, and `insightCards` compact. `themeDistribution` <= 8 rows, `themeTable` <= 6 rows, `representativeBooks` <= 2 per theme, `hotBooks` <= 5 items, `systemArchetypes` <= 6 items, `microInnovationSignals` <= 3 items, `insightCards` = 4 items, `historicalWordCloud` <= 20 items, and every reason/note string should stay within 60 Chinese characters.
        11. `detailContent` must be plain prose without markdown tables or code fences, and should stay within 600 Chinese characters.
        """;
    private static final long STREAM_PROGRESS_INITIAL_DELAY_MILLIS = 1200L;
    private static final long STREAM_PROGRESS_INTERVAL_MILLIS = 1500L;
    private static final String STREAM_PROGRESS_DELTA = "[analysis-progress] 正在分析中，请稍候...";

    private final RestTemplate aiRestTemplate;
    private final AiProperties aiProperties;
    private final SystemConfigService systemConfigService;
    private final UserConfigService userConfigService;
    private final TokenCountEstimator tokenCountEstimator;
    private final ObjectMapper objectMapper;

    // 模型实例缓存：key = baseUrl|modelName|apiKey|timeoutMillis，避免每次请求重建
    private final ConcurrentHashMap<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OpenAiStreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();

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
        return analyze(promptConfig, text, analysisType, null);
    }

    public AiInvokeResult analyze(PromptConfigEntity promptConfig,
                                  String text,
                                  String analysisType,
                                  Integer timeoutOverrideMillis) {
        String renderedPrompt = renderPrompt(promptConfig.getPromptContent(), text, analysisType);
        AiInvokeResult providerResult = invokePreferredProvider(
            promptConfig,
            text,
            renderedPrompt,
            analysisType,
            timeoutOverrideMillis
        );
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
                                                   String analysisType,
                                                   Integer timeoutOverrideMillis) {
        String providerType = resolveProviderType();
        if ("dify".equalsIgnoreCase(providerType)) {
            AiInvokeResult difyResult = invokeDify(promptConfig, renderedPrompt, analysisType);
            if (difyResult != null) {
                return difyResult;
            }
            return invokeOpenAiCompatible(promptConfig, text, analysisType, timeoutOverrideMillis);
        }

        AiInvokeResult openAiResult = invokeOpenAiCompatible(promptConfig, text, analysisType, timeoutOverrideMillis);
        if (openAiResult != null) {
            return openAiResult;
        }
        return invokeDify(promptConfig, renderedPrompt, analysisType);
    }

    private AiInvokeResult invokeOpenAiCompatible(PromptConfigEntity promptConfig,
                                                  String text,
                                                  String analysisType,
                                                  Integer timeoutOverrideMillis) {
        OpenAiCompatibleRuntime runtime = resolveOpenAiCompatibleRuntime(promptConfig);
        if (runtime.apiKey() == null || runtime.apiKey().isBlank()) {
            return null;
        }
        if (runtime.modelName() == null || runtime.modelName().isBlank()) {
            return null;
        }

        try {
            PromptMessagePair promptMessages = buildPromptMessagePair(promptConfig, text, analysisType);
            OpenAiChatModel model = getOrCreateChatModel(
                runtime.baseUrl(), runtime.apiKey(), runtime.modelName(),
                runtime.temperature(), runtime.maxTokens(), timeoutOverrideMillis
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
            Map<String, Object> resultJson = buildStructuredResult(promptConfig, Map.of(), content, analysisType, runtime.modelName());
            return AiInvokeResult.of(
                runtime.modelName(),
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
                                   int timeoutOverrideMillis,
                                   SseEmitter emitter,
                                   InvocationHandle invocationHandle,
                                   BiConsumer<SseEmitter, AiInvokeResult> onDone,
                                   Consumer<Throwable> onError) {
        if (!isOpenAiCompatibleStreamingEnabled()) {
            return false;
        }
        OpenAiCompatibleRuntime runtime = resolveOpenAiCompatibleRuntime(promptConfig);
        if (runtime.apiKey() == null || runtime.apiKey().isBlank()
            || runtime.modelName() == null || runtime.modelName().isBlank()) {
            return false;
        }

        PromptMessagePair promptMessages = buildPromptMessagePair(promptConfig, text, analysisType);
        OpenAiStreamingChatModel streamModel = getOrCreateStreamingChatModel(
            runtime.baseUrl(), runtime.apiKey(), runtime.modelName(),
            runtime.temperature(), runtime.maxTokens(), timeoutOverrideMillis
        );

        StringBuilder buffer = new StringBuilder();
        final String resolvedModel = runtime.modelName();
        AtomicBoolean waitingForFirstDelta = new AtomicBoolean(true);
        ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "analysis-stream-progress");
            thread.setDaemon(true);
            return thread;
        });
        progressExecutor.scheduleAtFixedRate(() -> {
            if (invocationHandle.isCancelled() || !waitingForFirstDelta.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                    .name("delta")
                    .data(Map.of("event", "delta", "delta", STREAM_PROGRESS_DELTA)));
            } catch (IOException ignored) {
                invocationHandle.cancel();
            }
        }, STREAM_PROGRESS_INITIAL_DELAY_MILLIS, STREAM_PROGRESS_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

        ChatRequest.Builder requestBuilder = ChatRequest.builder()
            .messages(promptMessages.messages());
        ResponseFormat responseFormat = resolveResponseFormat(promptConfig);
        if (responseFormat != null) {
            requestBuilder.responseFormat(responseFormat);
        }

        streamModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext partialResponseContext) {
                String token = partialResponse == null ? "" : partialResponse.text();
                StreamingHandle streamingHandle = partialResponseContext == null ? null : partialResponseContext.streamingHandle();
                invocationHandle.attachStreamingHandle(streamingHandle);
                if (invocationHandle.isCancelled()) {
                    if (streamingHandle != null) {
                        streamingHandle.cancel();
                    }
                    return;
                }
                if (token != null && !token.isBlank()) {
                    waitingForFirstDelta.set(false);
                }
                buffer.append(token);
                try {
                    emitter.send(SseEmitter.event()
                        .name("delta")
                        .data(Map.of("event", "delta", "delta", token)));
                } catch (IOException ignored) {
                    invocationHandle.cancel();
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                progressExecutor.shutdownNow();
                if (invocationHandle.isCancelled()) {
                    return;
                }
                waitingForFirstDelta.set(false);
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
                progressExecutor.shutdownNow();
                if (invocationHandle.isCancelled()) {
                    return;
                }
                waitingForFirstDelta.set(false);
                onError.accept(error);
            }
        });
        return true;
    }

    private PromptMessagePair buildPromptMessagePair(PromptConfigEntity promptConfig,
                                                     String text,
                                                     String analysisType) {
        if (!"experimental-shared-context".equals(System.getProperty("noval.analysis.prompt-layout"))) {
            return buildPromptMessagePairLegacy(promptConfig, text, analysisType);
        }
        /*
        String template = normalizePromptTemplate(promptConfig == null ? null : promptConfig.getPromptContent(), analysisType);
        if (!template.contains("{{content}}")) {
            return new PromptMessagePair(
                List.of(UserMessage.from(renderPrompt(template, text, analysisType))),
                renderPrompt(template, text, analysisType)
            );
        }

        String systemPrompt = PromptTemplate.from(template)
            .apply(Map.of(
                "content", "姝ｆ枃鍐呭浼氬湪涓嬩竴鏉?user message 涓彁渚涳紝璇峰彧鍩轰簬璇ユ鏂囧畬鎴愬垎鏋愩€?,
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
        */
        return buildPromptMessagePairLegacy(promptConfig, text, analysisType);
    }

    private PromptMessagePair buildPromptMessagePairLegacy(PromptConfigEntity promptConfig,
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
        if (!usesThemeStructuredContract(promptConfig)) {
            return systemPrompt;
        }
        StringBuilder builder = new StringBuilder(systemPrompt);
        if (hasInputJsonContract(promptConfig)) {
            builder.append("\n\ninput schema:\n").append(promptConfig.getInputJsonSchema());
            if (promptConfig.getInputExampleJson() != null && !promptConfig.getInputExampleJson().isBlank()) {
                builder.append("\ninput example:\n").append(promptConfig.getInputExampleJson());
            }
        }
        if (hasOutputJsonContract(promptConfig)) {
            builder.append("\n\noutput schema:\n").append(promptConfig.getOutputJsonSchema());
            if (promptConfig.getOutputExampleJson() != null && !promptConfig.getOutputExampleJson().isBlank()) {
                builder.append("\noutput example:\n").append(promptConfig.getOutputExampleJson());
            }
        }
        builder.append("\n\n").append(THEME_STRUCTURED_GUIDANCE);
        builder.append("\n\nPlease output valid JSON only.");
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
        if (!usesThemeStructuredContract(promptConfig)) {
            return false;
        }
        if ("json_extract".equalsIgnoreCase(promptConfig.getPostProcessType())) {
            return true;
        }
        if (promptConfig.getParseConfigJson() != null
            && promptConfig.getParseConfigJson().toLowerCase(java.util.Locale.ROOT).contains("\"parser\":\"json\"")) {
            return true;
        }
        return true;
    }

    private boolean hasInputJsonContract(PromptConfigEntity promptConfig) {
        if (promptConfig == null || !usesThemeStructuredContract(promptConfig)) {
            return false;
        }
        return promptConfig.getInputJsonSchema() != null && !promptConfig.getInputJsonSchema().isBlank();
    }

    private boolean hasOutputJsonContract(PromptConfigEntity promptConfig) {
        if (promptConfig == null || !usesThemeStructuredContract(promptConfig)) {
            return false;
        }
        return promptConfig.getOutputJsonSchema() != null && !promptConfig.getOutputJsonSchema().isBlank();
    }

    private boolean usesThemeStructuredContract(PromptConfigEntity promptConfig) {
        if (promptConfig == null || promptConfig.getPromptType() == null) {
            return false;
        }
        return "theme".equalsIgnoreCase(promptConfig.getPromptType().trim());
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
        if (!aiProperties.getOpenAiCompatible().isStreamingEnabled()) {
            return false;
        }
        return systemConfigService.getBooleanValueOrDefault(
            "ai.openai-compatible.streaming-enabled",
            true
        );
    }

    private OpenAiChatModel getOrCreateChatModel(String baseUrl, String apiKey,
                                                 String modelName,
                                                 Double temperature,
                                                 Integer maxTokens,
                                                 Integer timeoutOverrideMillis) {
        int timeoutMillis = resolveTimeoutMillis(timeoutOverrideMillis);
        String key = baseUrl + "|" + modelName + "|" + timeoutMillis
            + "|" + temperature + "|" + maxTokens + "|" + Integer.toHexString(apiKey.hashCode());
        return chatModelCache.computeIfAbsent(key, k -> {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMillis(timeoutMillis))
                .logRequests(false)
                .logResponses(false);
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null) builder.maxTokens(maxTokens);
            return builder.build();
        });
    }

    private OpenAiStreamingChatModel getOrCreateStreamingChatModel(String baseUrl, String apiKey,
                                                                   String modelName,
                                                                   Double temperature,
                                                                   Integer maxTokens,
                                                                   Integer timeoutOverrideMillis) {
        int timeoutMillis = resolveTimeoutMillis(timeoutOverrideMillis);
        String key = baseUrl + "|" + modelName + "|" + timeoutMillis
            + "|" + temperature + "|" + maxTokens + "|" + Integer.toHexString(apiKey.hashCode());
        return streamingModelCache.computeIfAbsent(key, k -> {
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMillis(timeoutMillis))
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

    private int resolveTimeoutMillis(Integer timeoutOverrideMillis) {
        int configuredTimeout = resolveTimeoutMillis();
        if (timeoutOverrideMillis == null || timeoutOverrideMillis <= 0) {
            return configuredTimeout;
        }
        return Math.max(configuredTimeout, timeoutOverrideMillis);
    }

    private String resolveOpenAiCompatibleBaseUrl() {
        String baseUrl = systemConfigService.getValueOrDefault(
            "ai.openai-compatible.base-url",
            aiProperties.getOpenAiCompatible().getBaseUrl()
        );
        return firstNonBlank(baseUrl, aiProperties.getOpenAiCompatible().getBaseUrl());
    }

    private String resolveOpenAiCompatibleApiKey() {
        String systemValue = systemConfigService.getValueOrDefault("ai.openai-compatible.api-key", null);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        return resolveSecretValue(aiProperties.getOpenAiCompatible().getApiKeyRef());
    }

    private OpenAiCompatibleRuntime resolveOpenAiCompatibleRuntime(PromptConfigEntity promptConfig) {
        String userPreferredModel = resolveUserPreferredModel();
        String promptConfiguredModel = resolvePromptConfiguredModel(promptConfig);
        Optional<AiModelRegistryModelVO> registryModel = systemConfigService.resolveEnabledModel(
            userPreferredModel,
            promptConfiguredModel
        );
        if (registryModel.isPresent()) {
            AiModelRegistryModelVO model = registryModel.get();
            return new OpenAiCompatibleRuntime(
                firstNonBlank(model.getModelName(), model.getModelKey()),
                firstNonBlank(model.getBaseUrl(), resolveOpenAiCompatibleBaseUrl()),
                firstNonBlank(model.getApiKey(), resolveOpenAiCompatibleApiKey()),
                promptConfig != null && promptConfig.getTemperature() != null
                    ? promptConfig.getTemperature() : model.getDefaultTemperature(),
                promptConfig != null && promptConfig.getMaxTokens() != null
                    ? promptConfig.getMaxTokens() : model.getMaxTokens()
            );
        }
        return new OpenAiCompatibleRuntime(
            firstNonBlank(promptConfiguredModel, resolveOpenAiCompatibleModelNameLegacy()),
            resolveOpenAiCompatibleBaseUrl(),
            resolveOpenAiCompatibleApiKey(),
            promptConfig == null ? null : promptConfig.getTemperature(),
            promptConfig == null ? null : promptConfig.getMaxTokens()
        );
    }

    private String resolveUserPreferredModel() {
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
        return null;
    }

    private String resolvePromptConfiguredModel(PromptConfigEntity promptConfig) {
        if (promptConfig == null) {
            return null;
        }
        String configuredModel = promptConfig.getModelName();
        if (configuredModel == null || configuredModel.isBlank() || "dify".equalsIgnoreCase(configuredModel)) {
            return null;
        }
        return configuredModel;
    }

    private String resolveOpenAiCompatibleModelNameLegacy() {
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

    private record OpenAiCompatibleRuntime(String modelName,
                                           String baseUrl,
                                           String apiKey,
                                           Double temperature,
                                           Integer maxTokens) {
    }

    public static final class InvocationHandle {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();

        public void attachStreamingHandle(StreamingHandle handle) {
            if (handle == null) {
                return;
            }
            streamingHandle.set(handle);
            if (isCancelled()) {
                handle.cancel();
            }
        }

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            StreamingHandle handle = streamingHandle.get();
            if (handle != null) {
                handle.cancel();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}

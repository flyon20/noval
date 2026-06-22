package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatService.class);
    private static final int MAX_QUESTION_LENGTH = 64_000;
    private static final int MAX_MEMORY_LAST_QUESTION_LENGTH = 1_000;
    private static final int MAX_CONTEXT_SUMMARY_LENGTH = 900_000;
    private static final int MAX_HISTORY_ITEMS = 12;
    private static final int MAX_HISTORY_CONTENT_LENGTH = 64_000;
    private static final int MAX_TIMEOUT_MILLIS = 600_000;
    private static final int MAX_TOOL_TIMEOUT_MILLIS = 600_000;
    private static final int FALLBACK_STREAM_CHUNK_SIZE = 96;

    private final LangGraphWorkerClient langGraphWorkerClient;
    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;
    private final CrawlerService crawlerService;
    private final AsyncTaskExecutor streamTaskExecutor;
    private final KnowledgeChatMemoryStore chatMemoryStore;
    private final KnowledgeAgentTraceService agentTraceService;
    private final KnowledgeProjectService projectService;
    private final KnowledgeMemoryCandidateService memoryCandidateService;
    private final Map<String, ChatMemory> fallbackMemoryStore = new ConcurrentHashMap<>();

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, null, null, null, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore, null, null, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore, agentTraceService, null, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService,
                                KnowledgeProjectService projectService) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore, agentTraceService, projectService, null);
    }

    @Autowired
    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService,
                                KnowledgeProjectService projectService,
                                KnowledgeMemoryCandidateService memoryCandidateService) {
        this.langGraphWorkerClient = langGraphWorkerClient;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
        this.crawlerService = crawlerService;
        this.streamTaskExecutor = streamTaskExecutor;
        this.chatMemoryStore = chatMemoryStore;
        this.agentTraceService = agentTraceService;
        this.projectService = projectService;
        this.memoryCandidateService = memoryCandidateService;
    }

    public KnowledgeChatResponseVO chat(KnowledgeChatRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        try {
            ensureProjectOwned(authUser, request);
            Long completedBookId = completeSelectedCandidateIfNeeded(request);
            AsyncJobSubmitResponse completedIndexJob = indexCompletedCandidateIfNeeded(completedBookId, authUser.getUserId());
            String conversationId = resolveConversationId(request);
            bindProjectConversation(authUser, request, conversationId);
            Map<String, Object> payload = buildWorkerPayload(request, authUser.getUserId(), completedBookId, conversationId);
            KnowledgeChatResponseVO response = langGraphWorkerClient.runKnowledgeChat(payload);
            attachConversationId(response, conversationId);
            attachCompletedBookId(response, completedBookId);
            attachCompletedIndexJob(response, completedIndexJob);
            updateChatMemory(conversationId, authUser.getUserId(), request, response);
            persistAgentTrace(authUser.getUserId(), request, conversationId, response);
            persistMemoryCandidates(authUser.getUserId(), request, response);
            maybeSubmitIndexJob(request, response, authUser.getUserId(), completedBookId);
            maybeSubmitChapterMissingIndexJob(response, authUser.getUserId());
            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ResultCode.BAD_GATEWAY, "knowledge candidate continuation failed");
        }
    }

    public SseEmitter streamChat(KnowledgeChatRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String traceId = TraceIdHolder.get();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        streamTaskExecutor.execute(() -> {
            try {
                AuthUserHolder.set(authUser);
                if (traceId != null && !traceId.isBlank()) {
                    TraceIdHolder.set(traceId);
                }
                ensureProjectOwned(authUser, request);
                emitter.send(SseEmitter.event().name("start").data(Map.of("event", "start", "traceId", traceId == null ? "" : traceId)));
                sendProgress(emitter, "prepare", "\u6b63\u5728\u51c6\u5907\u77e5\u8bc6\u5e93\u95ee\u7b54");
                Long completedBookId = completeSelectedCandidateIfNeeded(request);
                sendProgress(emitter, "index", completedBookId == null
                    ? "\u6b63\u5728\u68c0\u7d22\u77e5\u8bc6\u5e93"
                    : "\u6b63\u5728\u8865\u5168\u5e76\u7d22\u5f15\u9009\u4e2d\u4f5c\u54c1");
                AsyncJobSubmitResponse completedIndexJob = indexCompletedCandidateIfNeeded(completedBookId, authUser.getUserId());
                String conversationId = resolveConversationId(request);
                bindProjectConversation(authUser, request, conversationId);
                Map<String, Object> payload = buildWorkerPayload(request, authUser.getUserId(), completedBookId, conversationId);
                sendProgress(emitter, "retrieve", "\u6b63\u5728\u68c0\u7d22\u8d44\u6599\u5e76\u751f\u6210\u56de\u7b54");
                AtomicBoolean answerStarted = new AtomicBoolean(false);
                KnowledgeChatResponseVO response = streamWorkerWithInlineFallback(emitter, payload, conversationId, answerStarted, cancelled);
                if (response == null || cancelled.get()) {
                    return;
                }
                attachConversationId(response, conversationId);
                attachCompletedBookId(response, completedBookId);
                attachCompletedIndexJob(response, completedIndexJob);
                updateChatMemory(conversationId, authUser.getUserId(), request, response);
                persistAgentTrace(authUser.getUserId(), request, conversationId, response);
                persistMemoryCandidates(authUser.getUserId(), request, response);
                maybeSubmitIndexJob(request, response, authUser.getUserId(), completedBookId);
                maybeSubmitChapterMissingIndexJob(response, authUser.getUserId());
                emitter.send(SseEmitter.event().name("done").data(Map.of("event", "done", "data", response)));
                emitter.complete();
            } catch (Exception ex) {
                sendError(emitter, ex);
                emitter.complete();
            } finally {
                AuthUserHolder.clear();
                TraceIdHolder.clear();
            }
        });
        return emitter;
    }

    private KnowledgeChatResponseVO streamWorkerWithInlineFallback(SseEmitter emitter,
                                                                   Map<String, Object> payload,
                                                                   String conversationId,
                                                                   AtomicBoolean answerStarted,
                                                                   AtomicBoolean cancelled) {
        try {
            return langGraphWorkerClient.streamKnowledgeChat(
                payload,
                delta -> {
                    answerStarted.set(true);
                    sendDelta(emitter, delta);
                },
                (phase, message) -> sendProgress(emitter, phase, message),
                cancelled::get
            );
        } catch (Exception ex) {
            if (cancelled.get() || answerStarted.get()) {
                throw ex;
            }
            LOGGER.warn(
                "knowledge stream failed before first delta, switching to inline blocking fallback: conversationId={}, reason={}",
                conversationId,
                ex.getMessage()
            );
            sendProgress(emitter, "fallback", "\u6d41\u5f0f\u8fde\u63a5\u77ed\u6682\u4e2d\u65ad\uff0c\u6b63\u5728\u7a33\u5b9a\u751f\u6210\u5b8c\u6574\u56de\u7b54");
            KnowledgeChatResponseVO response = runBlockingFallbackWithHeartbeat(payload, emitter, cancelled);
            streamFallbackAnswerChunks(emitter, response, answerStarted, cancelled);
            return response;
        }
    }

    private KnowledgeChatResponseVO runBlockingFallbackWithHeartbeat(Map<String, Object> payload,
                                                                     SseEmitter emitter,
                                                                     AtomicBoolean cancelled) {
        CompletableFuture<KnowledgeChatResponseVO> future = CompletableFuture.supplyAsync(
            () -> langGraphWorkerClient.runKnowledgeChat(payload)
        );
        int heartbeat = 0;
        while (true) {
            if (cancelled.get()) {
                future.cancel(true);
                return null;
            }
            try {
                return future.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                heartbeat++;
                sendProgress(emitter, "fallback", "\u6b63\u5728\u7a33\u5b9a\u751f\u6210\u5b8c\u6574\u56de\u7b54 " + heartbeat);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "knowledge chat fallback interrupted");
            } catch (Exception ex) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "knowledge chat fallback failed");
            }
        }
    }

    private void streamFallbackAnswerChunks(SseEmitter emitter,
                                            KnowledgeChatResponseVO response,
                                            AtomicBoolean answerStarted,
                                            AtomicBoolean cancelled) {
        if (response == null || response.getAnswer() == null || response.getAnswer().isBlank() || cancelled.get()) {
            return;
        }
        String answer = response.getAnswer();
        for (int start = 0; start < answer.length() && !cancelled.get(); start += FALLBACK_STREAM_CHUNK_SIZE) {
            int end = Math.min(answer.length(), start + FALLBACK_STREAM_CHUNK_SIZE);
            answerStarted.set(true);
            sendDelta(emitter, answer.substring(start, end));
        }
    }

    private void sendDelta(SseEmitter emitter, String delta) {
        if (emitter == null || delta == null || delta.isBlank()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("delta").data(Map.of("event", "delta", "delta", delta)));
            emitter.send(SseEmitter.event().comment("flush"));
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private Map<String, Object> buildWorkerPayload(KnowledgeChatRequest request, Long userId, Long completedBookId, String conversationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String question = truncate(trimToNull(request.getQuestion()), MAX_QUESTION_LENGTH);
        Long effectiveBookId = completedBookId == null ? request.getBookId() : completedBookId;
        String effectiveBookName = trimToNull(request.getBookName());
        payload.put("question", question);
        putIfPresent(payload, "conversationId", conversationId);
        putIfPresent(payload, "projectId", request.getProjectId());
        putIfPresent(payload, "traceId", TraceIdHolder.get());
        putIfPresent(payload, "bookName", effectiveBookName);
        putIfPresent(payload, "bookId", effectiveBookId);
        if (completedBookId == null) {
            putIfPresent(payload, "selectedCandidate", toCandidatePayload(request.getSelectedCandidate()));
        } else if (trimToNull(request.getBookName()) == null && request.getSelectedCandidate() != null) {
            effectiveBookName = trimToNull(request.getSelectedCandidate().getBookName());
            putIfPresent(payload, "bookName", effectiveBookName);
        }
        putIfPresent(payload, "mode", trimToNull(request.getMode()));
        String contextSummary = resolveContextSummary(request, conversationId, userId);
        putIfPresent(payload, "contextSummary", contextSummary);
        List<Map<String, Object>> history = toHistoryPayload(request.getHistory());
        if (!history.isEmpty()) {
            payload.put("history", history);
        }
        putIfPresent(payload, "userId", userId);
        payload.put("contextBundle", buildContextBundle(
            question,
            request,
            userId,
            effectiveBookId,
            effectiveBookName,
            conversationId,
            contextSummary,
            history
        ));
        payload.put("limits", sanitizeLimits(request.getLimits()));
        return payload;
    }

    private Map<String, Object> buildContextBundle(String question,
                                                   KnowledgeChatRequest request,
                                                   Long userId,
                                                   Long effectiveBookId,
                                                   String effectiveBookName,
                                                   String conversationId,
                                                   String contextSummary,
                                                   List<Map<String, Object>> history) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("systemBaseline", contextLayer("system", Map.of(
            "domain", "webnovel",
            "rule", "Use project/thread memory as context, not as market fact evidence."
        )));
        if (request.getProjectId() != null) {
            Map<String, Object> projectContent = new LinkedHashMap<>();
            putIfPresent(projectContent, "projectId", request.getProjectId());
            putIfPresent(projectContent, "bookId", effectiveBookId);
            putIfPresent(projectContent, "bookName", effectiveBookName);
            bundle.put("projectProfile", contextLayer("project", projectContent));
        }
        if (contextSummary != null || (history != null && !history.isEmpty())) {
            Map<String, Object> threadContent = new LinkedHashMap<>();
            putIfPresent(threadContent, "conversationId", conversationId);
            putIfPresent(threadContent, "summary", contextSummary);
            if (history != null && !history.isEmpty()) {
                threadContent.put("history", history);
            }
            bundle.put("threadSummary", contextLayer("thread", threadContent));
        }
        Map<String, Object> turnContent = new LinkedHashMap<>();
        putIfPresent(turnContent, "question", question);
        putIfPresent(turnContent, "userId", userId);
        putIfPresent(turnContent, "projectId", request.getProjectId());
        putIfPresent(turnContent, "conversationId", conversationId);
        putIfPresent(turnContent, "bookId", effectiveBookId);
        putIfPresent(turnContent, "bookName", effectiveBookName);
        putIfPresent(turnContent, "mode", trimToNull(request.getMode()));
        bundle.put("currentTurn", contextLayer("turn", turnContent));
        return bundle;
    }

    private Map<String, Object> contextLayer(String scope, Map<String, Object> content) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("scope", scope);
        layer.put("content", content == null ? Map.of() : content);
        layer.put("sourceIds", List.of());
        return layer;
    }

    private void ensureProjectOwned(AuthUser authUser, KnowledgeChatRequest request) {
        if (projectService == null || request == null || request.getProjectId() == null) {
            return;
        }
        projectService.ensureOwned(request.getProjectId(), authUser.getUserId());
    }

    private void bindProjectConversation(AuthUser authUser, KnowledgeChatRequest request, String conversationId) {
        if (projectService == null || request == null || request.getProjectId() == null) {
            return;
        }
        projectService.bindConversation(request.getProjectId(), authUser.getUserId(), conversationId);
    }

    private String resolveConversationId(KnowledgeChatRequest request) {
        String existing = trimToNull(request.getConversationId());
        return existing == null ? UUID.randomUUID().toString() : existing;
    }

    private void sendProgress(SseEmitter emitter, String phase, String message) {
        if (emitter == null || trimToNull(message) == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("progress").data(Map.of(
                "event", "progress",
                "phase", trimToNull(phase) == null ? "running" : phase,
                "message", message
            )));
            emitter.send(SseEmitter.event().comment("flush"));
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private void sendError(SseEmitter emitter, Exception ex) {
        if (emitter == null) {
            return;
        }
        try {
            ResultCode resultCode = ex instanceof BusinessException businessException
                ? businessException.getResultCode()
                : ResultCode.INTERNAL_ERROR;
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                "event", "error",
                "code", resultCode.getCode(),
                "message", ex instanceof BusinessException ? ex.getMessage() : "knowledge chat stream failed"
            )));
        } catch (Exception ignored) {
            emitter.completeWithError(ignored);
        }
    }

    private String resolveContextSummary(KnowledgeChatRequest request, String conversationId, Long userId) {
        String requestSummary = trimToNull(request.getContextSummary());
        ChatMemory memory = findMemory(conversationId, userId);
        if (memory == null || !memory.userId().equals(userId) || trimToNull(memory.summary()) == null) {
            return truncate(requestSummary, MAX_CONTEXT_SUMMARY_LENGTH);
        }
        if (requestSummary == null) {
            return truncate(memory.summary(), MAX_CONTEXT_SUMMARY_LENGTH);
        }
        return truncate(memory.summary() + "\n" + requestSummary, MAX_CONTEXT_SUMMARY_LENGTH);
    }

    private void attachConversationId(KnowledgeChatResponseVO response, String conversationId) {
        if (response == null || conversationId == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("conversationId", conversationId);
        response.setResultJson(resultJson);
    }

    private void updateChatMemory(String conversationId,
                                  Long userId,
                                  KnowledgeChatRequest request,
                                  KnowledgeChatResponseVO response) {
        if (conversationId == null || response == null) {
            return;
        }
        String summary = trimToNull(String.valueOf(response.getResultJson().getOrDefault("memorySummary", "")));
        if (summary == null) {
            String answer = trimToNull(response.getAnswer());
            String question = trimToNull(request.getQuestion());
            summary = truncate(
                (question == null ? "" : "\u6700\u8fd1\u7528\u6237\u76ee\u6807\uff1a" + question)
                    + (answer == null ? "" : "\n\u4e0a\u4e00\u8f6e\u7ed3\u8bba\uff1a" + answer),
                1200
            );
        }
        String lastBookName = trimToNull(String.valueOf(response.getResultJson().getOrDefault("bookName", "")));
        String lastIntent = trimToNull(String.valueOf(response.getResultJson().getOrDefault("intent", "")));
        if (chatMemoryStore != null) {
            chatMemoryStore.save(
                conversationId,
                userId,
                summary,
                truncate(request.getQuestion(), MAX_MEMORY_LAST_QUESTION_LENGTH),
                response.getAnswer(),
                lastBookName,
                lastIntent
            );
        }
        fallbackMemoryStore.put(conversationId, new ChatMemory(userId, summary));
    }

    private void persistAgentTrace(Long userId,
                                   KnowledgeChatRequest request,
                                   String conversationId,
                                   KnowledgeChatResponseVO response) {
        if (agentTraceService == null || response == null || response.getResultJson() == null) {
            return;
        }
        Map<String, Object> resultJson = response.getResultJson();
        if (!resultJson.containsKey("taskGraph") && !resultJson.containsKey("toolRuns")) {
            return;
        }
        agentTraceService.persistFromChat(
            userId,
            request.getProjectId(),
            conversationId,
            request.getQuestion(),
            response
        );
    }

    private void persistMemoryCandidates(Long userId,
                                         KnowledgeChatRequest request,
                                         KnowledgeChatResponseVO response) {
        if (memoryCandidateService == null
            || userId == null
            || request == null
            || request.getProjectId() == null
            || response == null
            || response.getResultJson() == null) {
            return;
        }
        Object rawCandidates = response.getResultJson().get("memoryCandidates");
        if (!(rawCandidates instanceof List<?> rawList) || rawList.isEmpty()) {
            return;
        }
        List<Map<String, Object>> candidates = rawList.stream()
            .filter(Map.class::isInstance)
            .map(item -> {
                Map<?, ?> rawMap = (Map<?, ?>) item;
                Map<String, Object> candidate = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> {
                    if (key != null) {
                        candidate.put(String.valueOf(key), value);
                    }
                });
                return candidate;
            })
            .filter(candidate -> !candidate.isEmpty())
            .toList();
        if (candidates.isEmpty()) {
            return;
        }
        memoryCandidateService.persistCandidates(
            request.getProjectId(),
            userId,
            candidates,
            resolveResultTraceId(response.getResultJson())
        );
    }

    private String resolveResultTraceId(Map<String, Object> resultJson) {
        if (resultJson == null || resultJson.isEmpty()) {
            return null;
        }
        String traceId = trimToNull(stringValue(resultJson.get("traceId")));
        if (traceId != null) {
            return traceId;
        }
        Object trace = resultJson.get("trace");
        if (trace instanceof Map<?, ?> traceMap) {
            return trimToNull(stringValue(traceMap.get("traceId")));
        }
        return null;
    }

    private ChatMemory findMemory(String conversationId, Long userId) {
        if (chatMemoryStore != null) {
            return chatMemoryStore.find(conversationId, userId)
                .map(memory -> new ChatMemory(memory.userId(), memory.summary()))
                .orElseGet(() -> fallbackMemoryStore.get(conversationId));
        }
        return fallbackMemoryStore.get(conversationId);
    }

    private List<Map<String, Object>> toHistoryPayload(List<KnowledgeChatRequest.ChatMessageDTO> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
            .limit(MAX_HISTORY_ITEMS)
            .map(message -> {
                Map<String, Object> item = new LinkedHashMap<>();
                String role = trimToNull(message.getRole());
                String content = trimToNull(message.getContent());
                if (!"assistant".equals(role)) {
                    role = "user";
                }
                putIfPresent(item, "role", role);
                putIfPresent(item, "content", truncate(content, MAX_HISTORY_CONTENT_LENGTH));
                return item;
            })
            .filter(item -> item.containsKey("content"))
            .toList();
    }

    private Map<String, Object> sanitizeLimits(Map<String, Object> limits) {
        if (limits == null || limits.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        putLimitIfPresent(sanitized, limits, "chapterCount", 1, 10);
        putLimitIfPresent(sanitized, limits, "candidateLimit", 1, 20);
        putLimitIfPresent(sanitized, limits, "evidenceLimit", 1, 20);
        putLimitIfPresent(sanitized, limits, "chapterLimit", 1, 20);
        putLimitIfPresent(sanitized, limits, "analysisLimit", 1, 20);
        putLimitIfPresent(sanitized, limits, "rankLimit", 1, 20);
        putLimitIfPresent(sanitized, limits, "chapterLimitPerBook", 1, 5);
        putLimitIfPresent(sanitized, limits, "timeoutMillis", 1, MAX_TIMEOUT_MILLIS);
        putLimitIfPresent(sanitized, limits, "toolTimeoutMillis", 1, MAX_TOOL_TIMEOUT_MILLIS);
        return sanitized;
    }

    private void putLimitIfPresent(Map<String, Object> target,
                                   Map<String, Object> source,
                                   String key,
                                   int min,
                                   int max) {
        Integer value = parseInteger(source.get(key));
        if (value == null) {
            return;
        }
        target.put(key, Math.min(Math.max(value, min), max));
    }

    private Map<String, Object> toCandidatePayload(KnowledgeChatRequest.CandidateDTO candidate) {
        if (candidate == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "bookId", candidate.getBookId());
        putIfPresent(payload, "platform", trimToNull(candidate.getPlatform()));
        putIfPresent(payload, "platformBookId", trimToNull(candidate.getPlatformBookId()));
        putIfPresent(payload, "bookName", trimToNull(candidate.getBookName()));
        putIfPresent(payload, "author", trimToNull(candidate.getAuthor()));
        putIfPresent(payload, "intro", trimToNull(candidate.getIntro()));
        putIfPresent(payload, "bookUrl", trimToNull(candidate.getBookUrl()));
        putIfPresent(payload, "local", candidate.getLocal());
        return payload;
    }

    private void maybeSubmitIndexJob(KnowledgeChatRequest request,
                                     KnowledgeChatResponseVO response,
                                     Long userId,
                                     Long completedBookId) {
        if (response == null || response.getActions() == null || !response.getActions().contains("index_book")) {
            return;
        }
        if (completedBookId != null) {
            return;
        }
        Long bookId = completedBookId == null ? resolveIndexBookId(request, response) : completedBookId;
        if (bookId == null) {
            return;
        }
        AsyncJobSubmitResponse jobResponse = knowledgeIndexJobExecutor.submitAndExecute(bookId, userId);
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("localBookId", bookId);
        resultJson.put("indexJob", jobResponse);
        response.setResultJson(resultJson);
    }

    private void maybeSubmitChapterMissingIndexJob(KnowledgeChatResponseVO response, Long userId) {
        Long bookId = rawChapterFallbackBookId(response);
        if (bookId == null) {
            return;
        }
        AsyncJobSubmitResponse jobResponse = knowledgeIndexJobExecutor.submitAndExecute(bookId, userId, "CHAPTER_MISSING");
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("localBookId", bookId);
        resultJson.put("chapterIndexJob", jobResponse);
        response.setResultJson(resultJson);
    }

    private Long rawChapterFallbackBookId(KnowledgeChatResponseVO response) {
        if (response == null || response.getSources() == null || response.getSources().isEmpty()) {
            return null;
        }
        for (KnowledgeChatResponseVO.SourceVO source : response.getSources()) {
            if (!isRawChapterFallbackSource(source)) {
                continue;
            }
            if (source.getBookId() != null) {
                return source.getBookId();
            }
        }
        return null;
    }

    private boolean isRawChapterFallbackSource(KnowledgeChatResponseVO.SourceVO source) {
        return source != null
            && "CHAPTER".equalsIgnoreCase(trimToNull(source.getSourceType()))
            && source.getBookId() != null
            && source.getChunkId() == null
            && source.getDocumentId() == null;
    }

    private Long completeSelectedCandidateIfNeeded(KnowledgeChatRequest request) {
        KnowledgeChatRequest.CandidateDTO selectedCandidate = request.getSelectedCandidate();
        if (selectedCandidate == null) {
            return null;
        }
        if (selectedCandidate.getBookId() != null) {
            fetchLocalCandidateChaptersIfNeeded(selectedCandidate, request);
            return selectedCandidate.getBookId();
        }
        if (Boolean.TRUE.equals(selectedCandidate.getLocal())) {
            return null;
        }
        return crawlerService.completeExternalBookCandidate(
            trimToNull(selectedCandidate.getPlatform()),
            trimToNull(selectedCandidate.getPlatformBookId()),
            trimToNull(selectedCandidate.getBookName()),
            trimToNull(selectedCandidate.getAuthor()),
            trimToNull(selectedCandidate.getIntro()),
            trimToNull(selectedCandidate.getBookUrl()),
            resolveIndexChapterCount(request)
        );
    }

    private void fetchLocalCandidateChaptersIfNeeded(KnowledgeChatRequest.CandidateDTO selectedCandidate,
                                                     KnowledgeChatRequest request) {
        String platform = trimToNull(selectedCandidate.getPlatform());
        if (platform == null) {
            return;
        }
        CrawlerChapterRequest chapterRequest = new CrawlerChapterRequest();
        chapterRequest.setPlatform(platform);
        chapterRequest.setBookId(selectedCandidate.getBookId());
        chapterRequest.setChapterCount(resolveIndexChapterCount(request));
        crawlerService.getChapters(chapterRequest);
    }

    private AsyncJobSubmitResponse indexCompletedCandidateIfNeeded(Long completedBookId, Long userId) {
        if (completedBookId == null) {
            return null;
        }
        return knowledgeIndexJobExecutor.submitAndExecuteBlocking(completedBookId, userId);
    }

    private void attachCompletedBookId(KnowledgeChatResponseVO response, Long completedBookId) {
        if (response == null || completedBookId == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("localBookId", completedBookId);
        response.setResultJson(resultJson);
    }

    private void attachCompletedIndexJob(KnowledgeChatResponseVO response, AsyncJobSubmitResponse completedIndexJob) {
        if (response == null || completedIndexJob == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("indexJob", completedIndexJob);
        response.setResultJson(resultJson);
    }

    private Long resolveIndexBookId(KnowledgeChatRequest request, KnowledgeChatResponseVO response) {
        Long requestBookId = resolveLocalBookId(request);
        if (requestBookId != null) {
            return requestBookId;
        }
        if (response == null || response.getResultJson() == null) {
            return null;
        }
        Long responseBookId = toLong(response.getResultJson().get("localBookId"));
        if (responseBookId != null) {
            return responseBookId;
        }
        return toLong(response.getResultJson().get("bookId"));
    }

    private Long resolveLocalBookId(KnowledgeChatRequest request) {
        if (request.getBookId() != null) {
            return request.getBookId();
        }
        KnowledgeChatRequest.CandidateDTO selectedCandidate = request.getSelectedCandidate();
        if (selectedCandidate == null) {
            return null;
        }
        if (selectedCandidate.getBookId() != null) {
            return selectedCandidate.getBookId();
        }
        if (Boolean.TRUE.equals(selectedCandidate.getLocal())) {
            return null;
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int resolveIndexChapterCount(KnowledgeChatRequest request) {
        Integer value = parseInteger(request.getLimits() == null ? null : request.getLimits().get("chapterCount"));
        return value == null ? 3 : Math.min(Math.max(value, 1), 10);
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, Math.max(maxLength, 0));
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private record ChatMemory(Long userId, String summary) {
    }
}

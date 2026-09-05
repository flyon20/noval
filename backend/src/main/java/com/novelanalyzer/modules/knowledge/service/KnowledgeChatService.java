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
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeChatRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatResponseVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatService.class);
    private static final int MAX_QUESTION_LENGTH = 64_000;
    private static final int MAX_MEMORY_LAST_QUESTION_LENGTH = 1_000;
    private static final int MAX_CONTEXT_SUMMARY_LENGTH = 900_000;
    private static final int MAX_PERSISTED_SUMMARY_LENGTH = 128_000;
    private static final int MAX_HISTORY_ITEMS = 512;
    private static final int MAX_HISTORY_CONTENT_LENGTH = 64_000;
    private static final int MAX_HISTORY_TOTAL_LENGTH = 1_100_000;
    private static final int MAX_TIMEOUT_MILLIS = 600_000;
    private static final int MAX_TOOL_TIMEOUT_MILLIS = 600_000;
    private static final int DEFAULT_MAX_INPUT_TOKENS = 300_000;
    private static final int MIN_MAX_INPUT_TOKENS = 4_096;
    private static final int MAX_MAX_INPUT_TOKENS = 1_200_000;
    private static final int DEFAULT_COMPACTION_THRESHOLD_PERCENT = 85;
    private static final int MIN_COMPACTION_THRESHOLD_PERCENT = 50;
    private static final int MAX_COMPACTION_THRESHOLD_PERCENT = 95;
    private static final int DEFAULT_RUN_TOKEN_BUDGET_PERCENT = 150;
    private static final int MIN_RUN_TOKEN_BUDGET_PERCENT = 50;
    private static final int MAX_RUN_TOKEN_BUDGET_PERCENT = 400;
    private static final int FALLBACK_STREAM_CHUNK_SIZE = 96;
    private static final long FALLBACK_POLL_TIMEOUT_MILLIS = 250L;
    private static final int FALLBACK_HEARTBEAT_TICKS = 40;
    // 规范档位标度。worker 的方言表决定每个模型实际报出哪几档，这里只做白名单：
    // xhigh 是 gpt-5.6 一代独有的档位，漏掉它会让前端选了却被这里静默丢掉。
    private static final Set<String> CANONICAL_REASONING_TIERS =
        Set.of("minimal", "low", "medium", "high", "xhigh", "max");
    // worker 把上游故障压成结构化标记回传，这里只放行枚举形状的片段：
    // errorType=<异常类名> upstream=<HTTP 状态码> code=<供应商错误码>
    // type=<错误类型> param=<被拒字段>。
    // provider 的自由文本 message 不进这条链路——它会回显请求里的字段值，
    // 而这一层无法核实 worker 是否已脱敏，留在 worker 日志里更合适。
    private static final Pattern UPSTREAM_DIAGNOSTIC = Pattern.compile(
        "(?i)(?:^|[\\s,;])(errorType|upstream|code|type|param)=([A-Za-z0-9_.:-]{1,64})"
    );
    private static final List<String> UPSTREAM_DIAGNOSTIC_KEYS =
        List.of("errorType", "upstream", "code", "type", "param");

    private final LangGraphWorkerClient langGraphWorkerClient;
    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;
    private final CrawlerService crawlerService;
    private final AsyncTaskExecutor streamTaskExecutor;
    private TaskExecutor preToolTaskExecutor;
    private final KnowledgeChatMemoryStore chatMemoryStore;
    private final KnowledgeAgentTraceService agentTraceService;
    private final KnowledgeProjectService projectService;
    private final KnowledgeMemoryCandidateService memoryCandidateService;
    private final SystemConfigService systemConfigService;
    private final KnowledgeConversationSummaryService conversationSummaryService;
    private final Executor fallbackExecutor;
    private final Map<String, ChatMemory> fallbackMemoryStore = new ConcurrentHashMap<>();

    public interface ChatProgressListener {
        default void onProgress(String phase, String message) {
        }

        default void onProgress(String phase, String message, Map<String, Object> details) {
            onProgress(phase, message);
        }

        default void onDelta(String delta) {
        }
    }

    public interface StreamLifecycleListener {
        default void onDelta(String delta) {
        }

        default void onProgress(String phase, String message, Map<String, Object> details) {
        }

        default void onCompleted(KnowledgeChatResponseVO response) {
        }

        default void onFailed(Exception error) {
        }
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, null, null, null, null, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore, null, null, null, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore, agentTraceService, null, null, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService,
                                KnowledgeProjectService projectService) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore, agentTraceService, projectService, null, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService,
                                KnowledgeProjectService projectService,
                                KnowledgeMemoryCandidateService memoryCandidateService) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore, agentTraceService, projectService, memoryCandidateService, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService,
                                KnowledgeProjectService projectService,
                                KnowledgeMemoryCandidateService memoryCandidateService,
                                SystemConfigService systemConfigService) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor, chatMemoryStore,
            agentTraceService, projectService, memoryCandidateService, systemConfigService, null);
    }

    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService,
                                KnowledgeProjectService projectService,
                                KnowledgeMemoryCandidateService memoryCandidateService,
                                SystemConfigService systemConfigService,
                                KnowledgeConversationSummaryService conversationSummaryService) {
        this(langGraphWorkerClient, knowledgeIndexJobExecutor, crawlerService, streamTaskExecutor,
            chatMemoryStore, agentTraceService, projectService, memoryCandidateService, systemConfigService,
            conversationSummaryService, null);
    }

    @Autowired
    public KnowledgeChatService(LangGraphWorkerClient langGraphWorkerClient,
                                KnowledgeIndexJobExecutor knowledgeIndexJobExecutor,
                                CrawlerService crawlerService,
                                @Qualifier("analysisStreamTaskExecutor") AsyncTaskExecutor streamTaskExecutor,
                                KnowledgeChatMemoryStore chatMemoryStore,
                                KnowledgeAgentTraceService agentTraceService,
                                KnowledgeProjectService projectService,
                                KnowledgeMemoryCandidateService memoryCandidateService,
                                SystemConfigService systemConfigService,
                                KnowledgeConversationSummaryService conversationSummaryService,
                                @Qualifier("knowledgeChatFallbackExecutor") Executor fallbackExecutor) {
        this.langGraphWorkerClient = langGraphWorkerClient;
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
        this.crawlerService = crawlerService;
        this.streamTaskExecutor = streamTaskExecutor;
        this.preToolTaskExecutor = streamTaskExecutor;
        this.chatMemoryStore = chatMemoryStore;
        this.agentTraceService = agentTraceService;
        this.projectService = projectService;
        this.memoryCandidateService = memoryCandidateService;
        this.systemConfigService = systemConfigService;
        this.conversationSummaryService = conversationSummaryService;
        this.fallbackExecutor = fallbackExecutor == null ? Runnable::run : fallbackExecutor;
    }

    @Autowired
    void configurePreToolTaskExecutor(
        @Qualifier("knowledgeIndexTaskExecutor") ObjectProvider<TaskExecutor> executorProvider
    ) {
        TaskExecutor configured = executorProvider.getIfAvailable();
        if (configured != null) {
            this.preToolTaskExecutor = configured;
        }
    }

    public KnowledgeChatResponseVO chatWithProgress(KnowledgeChatRequest request,
                                                    ChatProgressListener progressListener,
                                                    BooleanSupplier cancelledSupplier) {
        return executeChatWithProgress(request, progressListener, cancelledSupplier, true, true);
    }

    public KnowledgeChatResponseVO chatWithProgressForDurableRun(KnowledgeChatRequest request,
                                                                  ChatProgressListener progressListener,
                                                                  BooleanSupplier cancelledSupplier) {
        return executeChatWithProgress(request, progressListener, cancelledSupplier, false, false);
    }

    private KnowledgeChatResponseVO executeChatWithProgress(KnowledgeChatRequest request,
                                                            ChatProgressListener progressListener,
                                                            BooleanSupplier cancelledSupplier,
                                                            boolean persistArtifacts,
                                                            boolean allowBlockingFallback) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        ChatProgressListener listener = progressListener == null ? new ChatProgressListener() { } : progressListener;
        BooleanSupplier cancelled = cancelledSupplier == null ? () -> false : cancelledSupplier;
        try {
            ensureProjectOwned(authUser, request);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            listener.onProgress("prepare", "\u6b63\u5728\u51c6\u5907\u77e5\u8bc6\u5e93\u95ee\u7b54");
            Long completedBookId = request.getSelectedCandidate() == null
                ? null
                : runCancellableStep(() -> completeSelectedCandidateIfNeeded(request), cancelled);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            listener.onProgress("index", completedBookId == null
                ? "\u6b63\u5728\u68c0\u7d22\u77e5\u8bc6\u5e93"
                : "\u6b63\u5728\u8865\u5168\u5e76\u7d22\u5f15\u9009\u4e2d\u4f5c\u54c1");
            AsyncJobSubmitResponse completedIndexJob = completedBookId == null
                ? null
                : runCancellableStep(
                    () -> indexCompletedCandidateIfNeeded(completedBookId, authUser.getUserId()),
                    cancelled
                );
            if (cancelled.getAsBoolean()) {
                return null;
            }
            String conversationId = resolveConversationId(request);
            bindProjectConversation(authUser, request, conversationId);
            Map<String, Object> payload = buildWorkerPayload(request, authUser.getUserId(), completedBookId, conversationId);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            listener.onProgress("retrieve", "\u6b63\u5728\u68c0\u7d22\u8d44\u6599\u5e76\u751f\u6210\u56de\u7b54");
            AtomicBoolean answerStarted = new AtomicBoolean(false);
            KnowledgeChatResponseVO response;
            try {
                response = langGraphWorkerClient.streamKnowledgeChat(
                    payload,
                    delta -> {
                        answerStarted.set(true);
                        listener.onDelta(delta);
                    },
                    (phase, message, details) -> listener.onProgress(phase, message, details),
                    cancelled
                );
            } catch (RuntimeException ex) {
                if (cancelled.getAsBoolean() || answerStarted.get()) {
                    throw ex;
                }
                if (!allowBlockingFallback) {
                    throw ex;
                }
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                listener.onProgress("fallback", "\u6d41\u5f0f\u8fde\u63a5\u77ed\u6682\u4e2d\u65ad\uff0c\u6b63\u5728\u7a33\u5b9a\u751f\u6210\u5b8c\u6574\u56de\u7b54");
                response = langGraphWorkerClient.runKnowledgeChat(payload);
            }
            if (response == null || cancelled.getAsBoolean()) {
                return null;
            }
            attachConversationId(response, conversationId);
            attachCompletedBookId(response, completedBookId);
            attachCompletedIndexJob(response, completedIndexJob);
            if (persistArtifacts) {
                persistCompletedRunArtifacts(
                    authUser.getUserId(), request, conversationId, response, completedBookId
                );
            }
            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ResultCode.BAD_GATEWAY, "knowledge candidate continuation failed");
        }
    }

    public void persistCompletedRunArtifacts(Long userId,
                                             KnowledgeChatRequest request,
                                             String conversationId,
                                             KnowledgeChatResponseVO response,
                                             Long completedBookId) {
        if (userId == null || request == null || response == null) {
            return;
        }
        persistCompletedRunDatabaseArtifacts(userId, request, conversationId, response);
        persistCompletedRunIndexArtifacts(userId, request, response, completedBookId);
    }

    public void persistCompletedRunDatabaseArtifacts(Long userId,
                                                     KnowledgeChatRequest request,
                                                     String conversationId,
                                                     KnowledgeChatResponseVO response) {
        if (userId == null || request == null || response == null) {
            return;
        }
        updateChatMemory(conversationId, userId, request, response);
        persistMemoryCandidates(userId, request, response);
        persistAgentTrace(userId, request, conversationId, response);
    }

    public void persistCompletedRunDatabaseArtifactsStrict(Long userId,
                                                           KnowledgeChatRequest request,
                                                           String conversationId,
                                                           KnowledgeChatResponseVO response) {
        if (userId == null || request == null || response == null) {
            return;
        }
        updateChatMemory(conversationId, userId, request, response, true);
        persistMemoryCandidates(userId, request, response);
        persistAgentTrace(userId, request, conversationId, response);
    }

    public void prepareMemoryCandidatesForDurablePersistence(Long userId,
                                                             KnowledgeChatRequest request,
                                                             KnowledgeChatResponseVO response) {
        persistMemoryCandidates(userId, request, response);
    }

    public void persistCompletedRunIndexArtifacts(Long userId,
                                                  KnowledgeChatRequest request,
                                                  KnowledgeChatResponseVO response,
                                                  Long completedBookId) {
        persistCompletedRunIndexArtifacts(userId, request, response, completedBookId, null);
    }

    public void persistCompletedRunIndexArtifacts(Long userId,
                                                  KnowledgeChatRequest request,
                                                  KnowledgeChatResponseVO response,
                                                  Long completedBookId,
                                                  String actionIdempotencyScope) {
        if (userId == null || request == null || response == null) {
            return;
        }
        maybeSubmitIndexJob(request, response, userId, completedBookId, actionIdempotencyScope);
        maybeSubmitChapterMissingIndexJob(response, userId, actionIdempotencyScope);
    }

    private <T> T runCancellableStep(java.util.concurrent.Callable<T> task,
                                     BooleanSupplier cancelled) {
        FutureTask<T> future = new FutureTask<>(task);
        preToolTaskExecutor.execute(future);
        while (true) {
            if (cancelled.getAsBoolean()) {
                future.cancel(true);
                return null;
            }
            try {
                return future.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Poll the durable cancellation signal without occupying the request thread indefinitely.
            } catch (InterruptedException ex) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("knowledge pre-processing was interrupted", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("knowledge pre-processing failed", cause);
            }
        }
    }

    public KnowledgeChatResponseVO chat(KnowledgeChatRequest request) {
        return executeBlockingChat(request, true);
    }

    public KnowledgeChatResponseVO chatForDurableCommit(KnowledgeChatRequest request) {
        return chatForDurableCommit(request, () -> false);
    }

    public KnowledgeChatResponseVO chatForDurableCommit(KnowledgeChatRequest request,
                                                        BooleanSupplier cancelledSupplier) {
        return executeBlockingChat(request, false, cancelledSupplier);
    }

    private KnowledgeChatResponseVO executeBlockingChat(KnowledgeChatRequest request,
                                                        boolean persistArtifacts) {
        return executeBlockingChat(request, persistArtifacts, () -> false);
    }

    private KnowledgeChatResponseVO executeBlockingChat(KnowledgeChatRequest request,
                                                        boolean persistArtifacts,
                                                        BooleanSupplier cancelledSupplier) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        BooleanSupplier cancelled = cancelledSupplier == null ? () -> false : cancelledSupplier;
        try {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            ensureProjectOwned(authUser, request);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            Long completedBookId = request.getSelectedCandidate() == null
                ? null
                : runCancellableStep(() -> completeSelectedCandidateIfNeeded(request), cancelled);
            if (cancelled.getAsBoolean()) {
                return null;
            }
            AsyncJobSubmitResponse completedIndexJob = completedBookId == null
                ? null
                : runCancellableStep(
                    () -> indexCompletedCandidateIfNeeded(completedBookId, authUser.getUserId()),
                    cancelled
                );
            if (cancelled.getAsBoolean()) {
                return null;
            }
            String conversationId = resolveConversationId(request);
            bindProjectConversation(authUser, request, conversationId);
            Map<String, Object> payload = buildWorkerPayload(request, authUser.getUserId(), completedBookId, conversationId);
            KnowledgeChatResponseVO response = runCancellableStep(
                () -> langGraphWorkerClient.runKnowledgeChat(payload), cancelled
            );
            if (response == null || cancelled.getAsBoolean()) {
                return null;
            }
            attachConversationId(response, conversationId);
            attachCompletedBookId(response, completedBookId);
            attachCompletedIndexJob(response, completedIndexJob);
            if (persistArtifacts) {
                persistCompletedRunArtifacts(
                    authUser.getUserId(), request, conversationId, response, completedBookId
                );
            }
            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ResultCode.BAD_GATEWAY, "knowledge candidate continuation failed");
        }
    }

    public SseEmitter streamChat(KnowledgeChatRequest request) {
        return streamChat(request, new StreamLifecycleListener() { });
    }

    public SseEmitter streamChat(KnowledgeChatRequest request, StreamLifecycleListener lifecycleListener) {
        return streamChatInternal(request, lifecycleListener, true);
    }

    public SseEmitter streamChatForDurableCommit(KnowledgeChatRequest request,
                                                 StreamLifecycleListener lifecycleListener) {
        return streamChatForDurableCommit(request, lifecycleListener, () -> false);
    }

    public SseEmitter streamChatForDurableCommit(KnowledgeChatRequest request,
                                                 StreamLifecycleListener lifecycleListener,
                                                 BooleanSupplier cancelledSupplier) {
        return streamChatInternal(request, lifecycleListener, false, cancelledSupplier);
    }

    private SseEmitter streamChatInternal(KnowledgeChatRequest request,
                                          StreamLifecycleListener lifecycleListener,
                                          boolean persistArtifacts) {
        return streamChatInternal(request, lifecycleListener, persistArtifacts, () -> false);
    }

    private SseEmitter streamChatInternal(KnowledgeChatRequest request,
                                          StreamLifecycleListener lifecycleListener,
                                          boolean persistArtifacts,
                                          BooleanSupplier externalCancelledSupplier) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        SseEmitter emitter = new SseEmitter(0L);
        StreamLifecycleListener listener = lifecycleListener == null
            ? new StreamLifecycleListener() { }
            : lifecycleListener;
        AtomicBoolean cancelled = new AtomicBoolean(false);
        BooleanSupplier externalCancelled = externalCancelledSupplier == null
            ? () -> false
            : externalCancelledSupplier;
        BooleanSupplier cancellation = () -> cancelled.get() || externalCancelled.getAsBoolean();
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
                if (cancellation.getAsBoolean()) {
                    emitter.complete();
                    return;
                }
                emitter.send(SseEmitter.event().name("start").data(Map.of("event", "start", "traceId", traceId == null ? "" : traceId)));
                sendProgress(emitter, "prepare", "\u6b63\u5728\u51c6\u5907\u77e5\u8bc6\u5e93\u95ee\u7b54");
                Long completedBookId = request.getSelectedCandidate() == null
                    ? null
                    : runCancellableStep(() -> completeSelectedCandidateIfNeeded(request), cancellation);
                if (cancellation.getAsBoolean()) {
                    emitter.complete();
                    return;
                }
                sendProgress(emitter, "index", completedBookId == null
                    ? "\u6b63\u5728\u68c0\u7d22\u77e5\u8bc6\u5e93"
                    : "\u6b63\u5728\u8865\u5168\u5e76\u7d22\u5f15\u9009\u4e2d\u4f5c\u54c1");
                AsyncJobSubmitResponse completedIndexJob = completedBookId == null
                    ? null
                    : runCancellableStep(
                        () -> indexCompletedCandidateIfNeeded(completedBookId, authUser.getUserId()),
                        cancellation
                    );
                if (cancellation.getAsBoolean()) {
                    emitter.complete();
                    return;
                }
                String conversationId = resolveConversationId(request);
                bindProjectConversation(authUser, request, conversationId);
                Map<String, Object> payload = buildWorkerPayload(request, authUser.getUserId(), completedBookId, conversationId);
                sendProgress(emitter, "retrieve", "\u6b63\u5728\u68c0\u7d22\u8d44\u6599\u5e76\u751f\u6210\u56de\u7b54");
                AtomicBoolean answerStarted = new AtomicBoolean(false);
                KnowledgeChatResponseVO response = streamWorkerWithInlineFallback(
                    emitter, payload, conversationId, answerStarted, cancellation, listener
                );
                if (response == null || cancellation.getAsBoolean()) {
                    listener.onFailed(new IllegalStateException("knowledge chat stream cancelled"));
                    emitter.complete();
                    return;
                }
                attachConversationId(response, conversationId);
                attachCompletedBookId(response, completedBookId);
                attachCompletedIndexJob(response, completedIndexJob);
                if (persistArtifacts) {
                    persistCompletedRunArtifacts(
                        authUser.getUserId(), request, conversationId, response, completedBookId
                    );
                }
                listener.onCompleted(response);
                emitter.send(SseEmitter.event().name("done").data(Map.of("event", "done", "data", response)));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    listener.onFailed(ex);
                } catch (RuntimeException lifecycleError) {
                    LOGGER.warn("knowledge chat stream failure persistence failed: {}", lifecycleError.getMessage());
                }
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
                                                                   BooleanSupplier cancelled,
                                                                   StreamLifecycleListener lifecycleListener) {
        try {
            return langGraphWorkerClient.streamKnowledgeChat(
                payload,
                delta -> {
                    answerStarted.set(true);
                    lifecycleListener.onDelta(delta);
                    sendDelta(emitter, delta);
                },
                (phase, message, details) -> {
                    lifecycleListener.onProgress(phase, message, details);
                    sendProgress(emitter, phase, message, details);
                },
                cancelled
            );
        } catch (Exception ex) {
            if (cancelled.getAsBoolean() || answerStarted.get()) {
                throw ex;
            }
            LOGGER.warn(
                "knowledge stream failed before first delta, switching to inline blocking fallback: conversationId={}, reason={}",
                conversationId,
                ex.getMessage()
            );
            sendProgress(emitter, "fallback", "\u6d41\u5f0f\u8fde\u63a5\u77ed\u6682\u4e2d\u65ad\uff0c\u6b63\u5728\u7a33\u5b9a\u751f\u6210\u5b8c\u6574\u56de\u7b54");
            KnowledgeChatResponseVO response = runBlockingFallbackWithHeartbeat(
                payload, emitter, cancelled, conversationId
            );
            streamFallbackAnswerChunks(emitter, response, answerStarted, cancelled, lifecycleListener);
            return response;
        }
    }

    private KnowledgeChatResponseVO runBlockingFallbackWithHeartbeat(Map<String, Object> payload,
                                                                     SseEmitter emitter,
                                                                     BooleanSupplier cancelled,
                                                                     String conversationId) {
        FutureTask<KnowledgeChatResponseVO> future = new FutureTask<>(
            () -> langGraphWorkerClient.runKnowledgeChat(payload)
        );
        try {
            fallbackExecutor.execute(future);
        } catch (RuntimeException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "knowledge chat fallback unavailable");
        }
        int heartbeat = 0;
        int pollTicks = 0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                future.cancel(true);
                return null;
            }
            try {
                return future.get(FALLBACK_POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                pollTicks++;
                if (pollTicks % FALLBACK_HEARTBEAT_TICKS == 0) {
                    heartbeat++;
                    sendProgress(emitter, "fallback", "\u6b63\u5728\u7a33\u5b9a\u751f\u6210\u5b8c\u6574\u56de\u7b54 " + heartbeat);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "knowledge chat fallback interrupted");
            } catch (Exception ex) {
                // future.get() 抛的是 ExecutionException，根因在 cause 上。
                // 上游故障只保留结构化码位（upstream/code/type）：整条抹掉会让
                // ai_chat_run.error_message 只剩固定文案，故障后无从下手。
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                String diagnostic = extractUpstreamDiagnostic(cause);
                LOGGER.warn(
                    "knowledge chat fallback failed: conversationId={}, errorType={}, upstream={}, reason={}",
                    conversationId,
                    cause.getClass().getSimpleName(),
                    diagnostic == null ? "unclassified" : diagnostic,
                    cause instanceof BusinessException ? cause.getMessage() : "unavailable"
                );
                throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    diagnostic == null
                        ? "knowledge chat fallback failed"
                        : "knowledge chat fallback failed: " + diagnostic
                );
            }
        }
    }

    /**
     * 从 worker 回传的错误文案里挑出结构化的上游码位，按固定顺序拼回
     * {@code upstream=400 code=unsupported_value type=invalid_request_error param=reasoning.effort}。
     * 只有 worker 契约生成的 {@link BusinessException} 才解析；其余异常的 message
     * 来源不可控，一律不落库。
     */
    private static String extractUpstreamDiagnostic(Throwable cause) {
        if (!(cause instanceof BusinessException) || cause.getMessage() == null) {
            return null;
        }
        Matcher matcher = UPSTREAM_DIAGNOSTIC.matcher(cause.getMessage());
        Map<String, String> parts = new LinkedHashMap<>();
        while (matcher.find()) {
            parts.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        }
        StringBuilder builder = new StringBuilder();
        for (String key : UPSTREAM_DIAGNOSTIC_KEYS) {
            String value = parts.get(key.toLowerCase(Locale.ROOT));
            if (value == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(key).append('=').append(value);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private void streamFallbackAnswerChunks(SseEmitter emitter,
                                            KnowledgeChatResponseVO response,
                                            AtomicBoolean answerStarted,
                                            BooleanSupplier cancelled,
                                            StreamLifecycleListener lifecycleListener) {
        if (response == null || response.getAnswer() == null || response.getAnswer().isEmpty()
            || cancelled.getAsBoolean()) {
            return;
        }
        String answer = response.getAnswer();
        for (int start = 0; start < answer.length() && !cancelled.getAsBoolean(); start += FALLBACK_STREAM_CHUNK_SIZE) {
            int end = Math.min(answer.length(), start + FALLBACK_STREAM_CHUNK_SIZE);
            answerStarted.set(true);
            String delta = answer.substring(start, end);
            lifecycleListener.onDelta(delta);
            if (!cancelled.getAsBoolean()) {
                sendDelta(emitter, delta);
            }
        }
    }

    private void sendDelta(SseEmitter emitter, String delta) {
        if (emitter == null || delta == null || delta.isEmpty()) {
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
        putIfPresent(payload, "workId", request.getWorkId());
        List<Map<String, Object>> referenceWorks = resolveReferenceWorks(request, userId);
        if (!referenceWorks.isEmpty()) {
            payload.put("referenceWorks", referenceWorks);
        }
        putIfPresent(payload, "traceId", TraceIdHolder.get());
        putIfPresent(payload, "resumeFromCheckpoint", request.getResumeFromCheckpoint());
        putIfPresent(payload, "bookName", effectiveBookName);
        putIfPresent(payload, "bookId", effectiveBookId);
        if (completedBookId == null) {
            putIfPresent(payload, "selectedCandidate", toCandidatePayload(request.getSelectedCandidate()));
        } else if (trimToNull(request.getBookName()) == null && request.getSelectedCandidate() != null) {
            effectiveBookName = trimToNull(request.getSelectedCandidate().getBookName());
            putIfPresent(payload, "bookName", effectiveBookName);
        }
        putIfPresent(payload, "mode", trimToNull(request.getMode()));
        payload.put("reasoningMode", resolveReasoningMode(request));
        putIfPresent(payload, "reasoningEffort", normalizeReasoningEffort(request.getReasoningEffort()));
        putIfPresent(payload, "preferredSkillId", trimToNull(request.getPreferredSkillId()));
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
        payload.put("limits", sanitizeLimits(request.getLimits(), request.getModelKey()));
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
            putIfPresent(projectContent, "workId", request.getWorkId());
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
        putIfPresent(turnContent, "workId", request.getWorkId());
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
        if (request == null) {
            return;
        }
        if (request.getProjectId() == null) {
            if (request.getWorkId() != null || !request.getReferenceWorkIds().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "projectId is required when workId is provided");
            }
            return;
        }
        if (!request.getReferenceWorkIds().isEmpty() && request.getWorkId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "workId is required when reference works are provided");
        }
        if (projectService == null) {
            if (!request.getReferenceWorkIds().isEmpty()) {
                throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "reference work validation unavailable");
            }
            return;
        }
        projectService.ensureOwned(request.getProjectId(), authUser.getUserId());
        if (request.getWorkId() != null) {
            projectService.ensureWorkOwned(request.getProjectId(), request.getWorkId(), authUser.getUserId());
        }
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
        sendProgress(emitter, phase, message, Map.of());
    }

    private void sendProgress(SseEmitter emitter,
                              String phase,
                              String message,
                              Map<String, Object> details) {
        if (emitter == null || trimToNull(message) == null) {
            return;
        }
        try {
            String progressEvent = contextProgressEvent(details);
            String eventName = progressEvent == null ? "progress" : progressEvent;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", eventName);
            payload.put("phase", trimToNull(phase) == null ? "running" : phase);
            payload.put("message", message);
            if (details != null && !details.isEmpty()) {
                payload.putAll(details);
            }
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            emitter.send(SseEmitter.event().comment("flush"));
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private List<Map<String, Object>> resolveReferenceWorks(KnowledgeChatRequest request, Long userId) {
        if (request.getReferenceWorkIds().isEmpty()) {
            return List.of();
        }
        if (projectService == null) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "reference work validation unavailable");
        }
        return projectService.resolveReferenceWorks(
                userId,
                request.getReferenceWorkIds(),
                request.getProjectId(),
                request.getWorkId()
            ).stream()
            .map(scope -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("projectId", scope.projectId());
                payload.put("workId", scope.workId());
                payload.put("title", scope.title());
                return payload;
            })
            .toList();
    }

    private String contextProgressEvent(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        String event = trimToNull(stringValue(details.get("progressEvent")));
        return "context_compacting".equals(event) || "context_compacted".equals(event)
            ? event
            : null;
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
        updateChatMemory(conversationId, userId, request, response, false);
    }

    private void updateChatMemory(String conversationId,
                                  Long userId,
                                  KnowledgeChatRequest request,
                                  KnowledgeChatResponseVO response,
                                  boolean strict) {
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
            if (strict) {
                chatMemoryStore.saveStrict(
                    conversationId,
                    userId,
                    summary,
                    truncate(request.getQuestion(), MAX_MEMORY_LAST_QUESTION_LENGTH),
                    response.getAnswer(),
                    lastBookName,
                    lastIntent
                );
            } else {
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
        }
        updateConversationSummary(conversationId, userId, request, response, summary, strict);
        fallbackMemoryStore.put(conversationId, new ChatMemory(userId, summary));
    }

    private void updateConversationSummary(String conversationId,
                                           Long userId,
                                           KnowledgeChatRequest request,
                                           KnowledgeChatResponseVO response,
                                           String summary) {
        updateConversationSummary(conversationId, userId, request, response, summary, false);
    }

    private void updateConversationSummary(String conversationId,
                                           Long userId,
                                           KnowledgeChatRequest request,
                                           KnowledgeChatResponseVO response,
                                           String summary,
                                           boolean strict) {
        if (conversationSummaryService == null || request == null || response == null) {
            return;
        }
        try {
            conversationSummaryService.updateSummary(
                userId,
                request.getProjectId(),
                conversationId,
                buildConversationSummary(request, response, summary),
                resolveSourceTraceId(response)
            );
        } catch (Exception ex) {
            if (strict) {
                throw ex instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("conversation summary update failed", ex);
            }
            LOGGER.warn(
                "knowledge conversation summary update skipped: conversationId={}, reason={}",
                conversationId,
                ex.getMessage()
            );
        }
    }

    private String buildConversationSummary(KnowledgeChatRequest request,
                                            KnowledgeChatResponseVO response,
                                            String runtimeSummary) {
        String question = trimToNull(request == null ? null : request.getQuestion());
        String answer = trimToNull(response == null ? null : response.getAnswer());
        String compactedSummary = resolveCompactedSummary(response);
        StringBuilder builder = new StringBuilder();
        if (compactedSummary != null) {
            builder.append(compactedSummary);
        }
        if (question != null) {
            appendLine(builder, "\u6700\u8fd1\u7528\u6237\u76ee\u6807\uff1a" + question);
        }
        if (runtimeSummary != null) {
            appendLine(builder, "\u8fd0\u884c\u6458\u8981\uff1a" + runtimeSummary);
        }
        if (answer != null) {
            appendLine(builder, "\u4e0a\u4e00\u8f6e\u7ed3\u8bba\uff1a" + answer);
        }
        String merged = trimToNull(builder.toString());
        return merged == null ? runtimeSummary : truncate(merged, MAX_PERSISTED_SUMMARY_LENGTH);
    }

    private String resolveCompactedSummary(KnowledgeChatResponseVO response) {
        if (response == null || response.getResultJson() == null) {
            return null;
        }
        Object compactionValue = response.getResultJson().get("contextCompaction");
        if (!(compactionValue instanceof Map<?, ?> compaction)) {
            return null;
        }
        Object summaryValue = compaction.get("compactedSummary");
        return truncate(trimToNull(summaryValue == null ? null : String.valueOf(summaryValue)), MAX_PERSISTED_SUMMARY_LENGTH);
    }

    private void appendLine(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(value);
    }

    private String resolveSourceTraceId(KnowledgeChatResponseVO response) {
        String traceId = trimToNull(TraceIdHolder.get());
        if (traceId != null || response == null || response.getResultJson() == null) {
            return traceId;
        }
        Object trace = response.getResultJson().get("trace");
        if (trace instanceof Map<?, ?> traceMap) {
            Object value = traceMap.get("traceId");
            return trimToNull(value == null ? null : String.valueOf(value));
        }
        return null;
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
        if (response == null || response.getResultJson() == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        response.setResultJson(resultJson);
        try {
            if (memoryCandidateService == null || userId == null || request == null) return;
            Integer workerPersistedValue = parseInteger(resultJson.get("memoryCandidatesPersisted"));
            int workerPersisted = workerPersistedValue == null ? 0 : Math.max(0, workerPersistedValue);
            int workerFailed = workerMemoryPersistenceFailed(resultJson);
            Object rawCandidates = resultJson.get("memoryCandidatePayloads");
            if (!(rawCandidates instanceof List<?>)) rawCandidates = resultJson.get("memoryCandidates");
            if (workerPersisted > 0 && workerFailed == 0) return;
            if (!(rawCandidates instanceof List<?> rawList) || rawList.isEmpty()) return;
            List<Map<String, Object>> candidates = rawList.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<?, ?> rawMap = (Map<?, ?>) item;
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    rawMap.forEach((key, value) -> {
                        if (key != null) candidate.put(String.valueOf(key), value);
                    });
                    return candidate;
                })
                .filter(candidate -> !candidate.isEmpty())
                .toList();
            if (workerPersisted > 0) {
                Set<String> failedCandidateKeys = workerMemoryPersistenceFailedCandidateKeys(resultJson);
                if (!failedCandidateKeys.isEmpty()) {
                    candidates = candidates.stream()
                        .filter(candidate -> failedCandidateKeys.contains(trimToNull(stringValue(candidate.get("candidateKey")))))
                        .toList();
                } else {
                    Set<String> failedFactKeys = workerMemoryPersistenceFailedFactKeys(resultJson);
                    if (failedFactKeys.isEmpty()) return;
                    Map<String, Long> factKeyCounts = candidates.stream()
                        .map(candidate -> trimToNull(stringValue(candidate.get("factKey"))))
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.groupingBy(
                            key -> key,
                            LinkedHashMap::new,
                            java.util.stream.Collectors.counting()
                        ));
                    candidates = candidates.stream()
                        .filter(candidate -> {
                            String factKey = trimToNull(stringValue(candidate.get("factKey")));
                            return factKey != null
                                && failedFactKeys.contains(factKey)
                                && Objects.equals(factKeyCounts.get(factKey), 1L);
                        })
                        .toList();
                }
            }
            if (candidates.isEmpty()) return;
            int recovered = memoryCandidateService.persistCandidates(
                request.getProjectId(),
                userId,
                candidates,
                resolveResultTraceId(response.getResultJson())
            );
            if (recovered > 0) {
                resultJson.put("memoryCandidatesBackendRecovered", recovered);
                resultJson.put("memoryCandidatesPersisted", workerPersisted + recovered);
            }
        } catch (RuntimeException ex) {
            recordBackendMemoryPersistenceFailure(resultJson, ex);
            LOGGER.warn(
                "memory candidate backend fallback failed: errorType={}",
                ex.getClass().getSimpleName()
            );
        } finally {
            resultJson.remove("memoryCandidatePayloads");
        }
    }

    private void recordBackendMemoryPersistenceFailure(Map<String, Object> resultJson,
                                                       RuntimeException exception) {
        Map<String, Object> diagnostics = mutableStringMap(resultJson.get("memoryDiagnostics"));
        Map<String, Object> persistence = mutableStringMap(diagnostics.get("candidatePersistence"));
        String errorType = trimToNull(exception.getClass().getSimpleName());
        if (errorType == null) {
            errorType = RuntimeException.class.getSimpleName();
        }
        persistence.put("backendFallback", Map.of(
            "status", "failed",
            "errorType", errorType
        ));
        diagnostics.put("candidatePersistence", persistence);
        resultJson.put("memoryDiagnostics", diagnostics);
    }

    private Map<String, Object> mutableStringMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key != null) result.put(String.valueOf(key), item);
            });
        }
        return result;
    }

    private int workerMemoryPersistenceFailed(Map<String, Object> resultJson) {
        Object diagnosticsValue = resultJson.get("memoryDiagnostics");
        if (!(diagnosticsValue instanceof Map<?, ?> diagnostics)) return 0;
        Object persistenceValue = diagnostics.get("candidatePersistence");
        if (!(persistenceValue instanceof Map<?, ?> persistence)) return 0;
        Integer failed = parseInteger(persistence.get("failed"));
        return failed == null ? 0 : Math.max(0, failed);
    }

    private Set<String> workerMemoryPersistenceFailedFactKeys(Map<String, Object> resultJson) {
        return workerMemoryPersistenceFailureValues(resultJson, "factKey");
    }

    private Set<String> workerMemoryPersistenceFailedCandidateKeys(Map<String, Object> resultJson) {
        return workerMemoryPersistenceFailureValues(resultJson, "candidateKey");
    }

    private Set<String> workerMemoryPersistenceFailureValues(Map<String, Object> resultJson, String key) {
        Object diagnosticsValue = resultJson.get("memoryDiagnostics");
        if (!(diagnosticsValue instanceof Map<?, ?> diagnostics)) return Set.of();
        Object persistenceValue = diagnostics.get("candidatePersistence");
        if (!(persistenceValue instanceof Map<?, ?> persistence)) return Set.of();
        Object failuresValue = persistence.get("failures");
        if (!(failuresValue instanceof List<?> failures)) return Set.of();
        return failures.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(failure -> trimToNull(stringValue(failure.get(key))))
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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
        List<Map<String, Object>> sanitized = new ArrayList<>();
        int remainingChars = MAX_HISTORY_TOTAL_LENGTH;
        for (int index = history.size() - 1;
             index >= 0 && sanitized.size() < MAX_HISTORY_ITEMS && remainingChars > 0;
             index--) {
            KnowledgeChatRequest.ChatMessageDTO message = history.get(index);
            if (message == null) {
                continue;
            }
            String content = trimToNull(message.getContent());
            if (content == null) {
                continue;
            }
            String boundedContent = truncate(
                content,
                Math.min(MAX_HISTORY_CONTENT_LENGTH, remainingChars)
            );
            if (boundedContent == null || boundedContent.isBlank()) {
                continue;
            }
            String role = "assistant".equals(trimToNull(message.getRole())) ? "assistant" : "user";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("content", boundedContent);
            sanitized.add(0, item);
            remainingChars -= boundedContent.length();
        }
        return List.copyOf(sanitized);
    }

    private Map<String, Object> sanitizeLimits(Map<String, Object> limits, String requestedModelKey) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (limits != null && !limits.isEmpty()) {
            putLimitIfPresent(sanitized, limits, "chapterCount", 1, 10);
            putLimitIfPresent(sanitized, limits, "candidateLimit", 1, 20);
            putLimitIfPresent(sanitized, limits, "evidenceLimit", 1, 20);
            putLimitIfPresent(sanitized, limits, "chapterLimit", 1, 20);
            putLimitIfPresent(sanitized, limits, "analysisLimit", 1, 20);
            putLimitIfPresent(sanitized, limits, "rankLimit", 1, 50);
            putLimitIfPresent(sanitized, limits, "chapterLimitPerBook", 1, 5);
            putLimitIfPresent(sanitized, limits, "timeoutMillis", 1, MAX_TIMEOUT_MILLIS);
            putLimitIfPresent(sanitized, limits, "toolTimeoutMillis", 1, MAX_TOOL_TIMEOUT_MILLIS);
        }
        String normalizedModelKey = trimToNull(requestedModelKey);
        if (normalizedModelKey != null && systemConfigService != null) {
            // Forward the exact enabled registry identity together with its model name.
            // Never trust a client-supplied model name from limits.
            systemConfigService.resolveEnabledModelByKey(normalizedModelKey).ifPresent(model -> {
                putIfPresent(sanitized, "modelKey", trimToNull(model.getModelKey()));
                putIfPresent(sanitized, "modelName", trimToNull(model.getModelName()));
            });
        }
        putMaxInputTokens(sanitized, limits);
        putContextGovernancePercents(sanitized, limits);
        return sanitized;
    }

    private void putMaxInputTokens(Map<String, Object> target, Map<String, Object> source) {
        Integer requested = source == null ? null : parseInteger(source.get("maxInputTokens"));
        if (requested == null && source != null) {
            requested = parseInteger(source.get("max_input_tokens"));
        }
        int configured = systemConfigService == null
            ? DEFAULT_MAX_INPUT_TOKENS
            : systemConfigService.getIntValueOrDefault("ai.agent.runtime.max-total-input-tokens", DEFAULT_MAX_INPUT_TOKENS);
        int value = requested == null ? configured : requested;
        target.put("maxInputTokens", Math.min(Math.max(value, MIN_MAX_INPUT_TOKENS), MAX_MAX_INPUT_TOKENS));
    }

    /**
     * 把上下文压缩触发比例和 run 预算比例逐请求下发给 worker。
     *
     * <p>worker 侧消费这两个值的有两处：请求层压缩和 provider 信封层压缩。后者拿不到 request，
     * 且 ContextCompactor 是进程级单例，所以 worker 用 run 级 contextvar 承接——走 limits
     * 是唯一能让改配置立刻生效、又不和 checkpoint 里的 runtime_config 快照打架的路径。
     *
     * <p>RuntimeValueType 只有 STRING/INTEGER/BOOLEAN，所以比例一律用整数百分比。
     */
    private void putContextGovernancePercents(Map<String, Object> target, Map<String, Object> source) {
        target.put("compactionThresholdPercent", resolvePercent(
            source,
            "compactionThresholdPercent",
            "ai.agent.runtime.context-compaction-threshold-percent",
            DEFAULT_COMPACTION_THRESHOLD_PERCENT,
            MIN_COMPACTION_THRESHOLD_PERCENT,
            MAX_COMPACTION_THRESHOLD_PERCENT
        ));
        target.put("runTokenBudgetPercent", resolvePercent(
            source,
            "runTokenBudgetPercent",
            "ai.agent.runtime.run-token-budget-percent",
            DEFAULT_RUN_TOKEN_BUDGET_PERCENT,
            MIN_RUN_TOKEN_BUDGET_PERCENT,
            MAX_RUN_TOKEN_BUDGET_PERCENT
        ));
    }

    private int resolvePercent(Map<String, Object> source,
                               String requestKey,
                               String configKey,
                               int defaultValue,
                               int min,
                               int max) {
        Integer requested = source == null ? null : parseInteger(source.get(requestKey));
        int configured = systemConfigService == null
            ? defaultValue
            : systemConfigService.getIntValueOrDefault(configKey, defaultValue);
        int value = requested == null ? configured : requested;
        return Math.min(Math.max(value, min), max);
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
        putIfPresent(payload, "contentType", trimToNull(candidate.getContentType()));
        putIfPresent(payload, "readableNovel", candidate.getReadableNovel());
        putIfPresent(payload, "unavailableReason", trimToNull(candidate.getUnavailableReason()));
        return payload;
    }

    private String resolveReasoningMode(KnowledgeChatRequest request) {
        String requested = normalizeReasoningMode(request == null ? null : request.getReasoningMode());
        if (requested != null) {
            return requested;
        }
        String configured = systemConfigService == null
            ? "fast"
            : systemConfigService.getValueOrDefault("ai.knowledge.reasoning-mode.default", "fast");
        String normalized = normalizeReasoningMode(configured);
        return normalized == null ? "fast" : normalized;
    }

    private String normalizeReasoningMode(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase();
        if ("deep".equals(normalized) || "reasoning".equals(normalized) || "thinking".equals(normalized)) {
            return "deep";
        }
        if ("fast".equals(normalized) || "quick".equals(normalized) || "normal".equals(normalized)) {
            return "fast";
        }
        return null;
    }

    /**
     * 规范档位标度的白名单校验，不做供应商映射。
     *
     * <p>各家能接受的枚举不同（OpenAI 四档、Kimi/GLM 三档、Qwen 只有开关），收敛工作放在
     * worker 的方言表里；后端只负责把用户选的档位原样带过去，未知值当成「没选」丢掉，
     * 避免把无效枚举送到供应商换回 400。
     */
    private String normalizeReasoningEffort(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase();
        return CANONICAL_REASONING_TIERS.contains(normalized) ? normalized : null;
    }

    private void maybeSubmitIndexJob(KnowledgeChatRequest request,
                                     KnowledgeChatResponseVO response,
                                     Long userId,
                                     Long completedBookId,
                                     String actionIdempotencyScope) {
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
        AsyncJobSubmitResponse jobResponse = actionIdempotencyScope == null || actionIdempotencyScope.isBlank()
            ? knowledgeIndexJobExecutor.submitAndExecute(bookId, userId)
            : knowledgeIndexJobExecutor.submitAndExecute(
                bookId,
                userId,
                "ALL",
                actionIdempotencyScope + ":index-book"
            );
        Map<String, Object> resultJson = new LinkedHashMap<>(response.getResultJson());
        resultJson.put("localBookId", bookId);
        resultJson.put("indexJob", jobResponse);
        response.setResultJson(resultJson);
    }

    private void maybeSubmitChapterMissingIndexJob(KnowledgeChatResponseVO response,
                                                   Long userId,
                                                   String actionIdempotencyScope) {
        Long bookId = rawChapterFallbackBookId(response);
        if (bookId == null) {
            return;
        }
        AsyncJobSubmitResponse jobResponse = actionIdempotencyScope == null || actionIdempotencyScope.isBlank()
            ? knowledgeIndexJobExecutor.submitAndExecute(bookId, userId, "CHAPTER_MISSING")
            : knowledgeIndexJobExecutor.submitAndExecute(
                bookId,
                userId,
                "CHAPTER_MISSING",
                actionIdempotencyScope + ":chapter-missing"
            );
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
        if (Boolean.FALSE.equals(selectedCandidate.getReadableNovel())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "selected candidate is not a readable novel");
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

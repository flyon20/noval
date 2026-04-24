package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.utils.TrendResultJsonUtils;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.analysis.dto.AnalysisRequest;
import com.novelanalyzer.modules.analysis.dto.TrendAnalysisRequest;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.analysis.repository.AnalysisRepository;
import com.novelanalyzer.modules.analysis.vo.AnalysisResultVO;
import com.novelanalyzer.modules.analysis.vo.TrendAnalysisVO;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.config.service.PromptConfigService;
import com.novelanalyzer.modules.config.service.UserConfigService;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.service.CrawlerCacheService;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class AnalysisService {

    private static final long ANALYSIS_TTL_SECONDS = 30L * 24 * 3600;
    private static final long STREAM_TIMEOUT_MILLIS = 0L;
    private static final int STREAM_CHUNK_SIZE = 120;
    private static final int DEFAULT_CHUNK_MAX_INPUT_TOKENS = 6000;
    private static final int DEFAULT_CHUNK_TARGET_INPUT_TOKENS = 3500;
    private static final int SYSTEM_DEFAULT_CHUNK_MAX_INPUT_TOKENS = 32000;
    private static final int SYSTEM_DEFAULT_CHUNK_TARGET_INPUT_TOKENS = 24000;
    private static final int DEEPSEEK_CHUNK_MAX_INPUT_TOKENS = 100000;
    private static final int DEEPSEEK_CHUNK_TARGET_INPUT_TOKENS = 75000;
    private static final int DEFAULT_CHUNK_PARALLELISM = 3;
    private static final int TREND_LANGGRAPH_TIMEOUT_MILLIS = 180000;
    private static final int LARGE_BOOK_ANALYSIS_TIMEOUT_MILLIS = 60000;
    private static final int LARGE_BOOK_FORCE_CHUNK_CHAPTER_COUNT = 8;
    private static final int LARGE_BOOK_FORCE_CHUNK_SEGMENT_SIZE = 3;
    private static final int TREND_PROMPT_INTRO_MAX_LENGTH = 140;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptConfigRepository promptConfigRepository;
    private final PromptConfigService promptConfigService;
    private final CrawlerRepository crawlerRepository;
    private final AiGatewayService aiGatewayService;
    private final AnalysisRepository analysisRepository;
    private final CrawlerCacheService crawlerCacheService;
    private final CrawlerService crawlerService;
    private final LangGraphWorkerClient langGraphWorkerClient;
    private final com.novelanalyzer.modules.config.service.SystemConfigService systemConfigService;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor streamTaskExecutor;

    public AnalysisService(PromptConfigRepository promptConfigRepository,
                           PromptConfigService promptConfigService,
                           CrawlerRepository crawlerRepository,
                           AiGatewayService aiGatewayService,
                           AnalysisRepository analysisRepository,
                           CrawlerCacheService crawlerCacheService,
                           CrawlerService crawlerService,
                           LangGraphWorkerClient langGraphWorkerClient,
                           com.novelanalyzer.modules.config.service.SystemConfigService systemConfigService,
                           UserConfigService userConfigService,
                           ObjectMapper objectMapper,
                           AsyncTaskExecutor analysisStreamTaskExecutor) {
        this.promptConfigRepository = promptConfigRepository;
        this.promptConfigService = promptConfigService;
        this.crawlerRepository = crawlerRepository;
        this.aiGatewayService = aiGatewayService;
        this.analysisRepository = analysisRepository;
        this.crawlerCacheService = crawlerCacheService;
        this.crawlerService = crawlerService;
        this.langGraphWorkerClient = langGraphWorkerClient;
        this.systemConfigService = systemConfigService;
        this.userConfigService = userConfigService;
        this.objectMapper = objectMapper;
        this.streamTaskExecutor = analysisStreamTaskExecutor;
    }

    public AnalysisResultVO analyze(String analysisType, AnalysisRequest request) {
        PromptConfigEntity promptConfig = resolveRuntimePromptConfig(analysisType);
        boolean forceReanalyze = Boolean.TRUE.equals(request.getForceReanalyze());
        String cacheKey = buildAnalysisCacheKey(promptConfig, request.getBookId(), analysisType, request.getChapterCount());
        AnalysisResultVO cached = crawlerCacheService.get(cacheKey, new TypeReference<AnalysisResultVO>() {});
        if (!forceReanalyze && cached != null) {
            return cached;
        }
        if (!forceReanalyze) {
            AnalysisResultVO persisted = analysisRepository.findLatestReusable(
                    request.getPlatform(),
                    request.getBookId(),
                    analysisType,
                    request.getChapterCount(),
                    promptConfig.getId(),
                    resolveAnalysisReuseTime(promptConfig)
                )
                .map(this::toAnalysisResultVO)
                .orElse(null);
            if (persisted != null) {
                crawlerCacheService.put(cacheKey, persisted, ANALYSIS_TTL_SECONDS);
                return persisted;
            }
        }
        CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        List<ChapterVO> chapters = loadAnalysisChapters(request);
        if (chapters.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter content not found");
        }

        AiInvokeResult aiResult = isLangGraphRuntimeEnabled()
            ? invokeLangGraphBookAnalysis(promptConfig, book, chapters, analysisType)
            : invokeLegacyBookAnalysis(promptConfig, book, chapters, analysisType, request.getChapterCount());
        attachBookAnalysisMeta(aiResult, request.getChapterCount(), chapters.size());
        Long resultId = saveAnalysisResult(
            request.getPlatform(),
            request.getBookId(),
            analysisType,
            request.getChapterCount(),
            promptConfig.getId(),
            aiResult
        );

        AnalysisResultVO vo = new AnalysisResultVO();
        vo.setId(resultId);
        vo.setBookId(request.getBookId());
        vo.setAnalysisType(analysisType);
        vo.setModelName(aiResult.getModelName());
        vo.setResultContent(aiResult.getContent());
        vo.setResultJson(aiResult.getResultJson());
        vo.setTokenUsed(aiResult.getTokenUsed());
        crawlerCacheService.put(cacheKey, vo, ANALYSIS_TTL_SECONDS);
        return vo;
    }

    public SseEmitter streamAnalyze(String analysisType, AnalysisRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AuthUser authUser = copyAuthUser(AuthUserHolder.get());
        String traceId = TraceIdHolder.get();
        AiGatewayService.InvocationHandle invocationHandle = new AiGatewayService.InvocationHandle();
        registerEmitterLifecycle(emitter, invocationHandle);
        streamTaskExecutor.execute(() -> {
            try {
                restoreContext(authUser, traceId);
                sendStartEvent(emitter, traceId, analysisType);
                ensureNotCancelled(invocationHandle);

                // 尝试真流式：非 chunk 场景直接推 token，避免等全文再切割
                PromptConfigEntity promptConfig = resolveRuntimePromptConfig(analysisType);
                List<ChapterVO> chapters = loadAnalysisChapters(request);
                if (chapters.isEmpty()) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "chapter content not found");
                }
                CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
                if (isLangGraphRuntimeEnabled()) {
                    AnalysisResultVO result = streamLangGraphBookAnalysis(
                        emitter,
                        request,
                        promptConfig,
                        book,
                        chapters,
                        analysisType,
                        invocationHandle
                    );
                    ensureNotCancelled(invocationHandle);
                    sendDoneEvent(emitter, result);
                    emitter.complete();
                    return;
                }
                String inputText = buildBookInputText(book, chapters);
                boolean useChunk = shouldUseChunkedAnalysis(promptConfig, analysisType, inputText, chapters);
                int analysisTimeoutMillis = resolveBookAnalysisTimeoutMillis(
                    request.getChapterCount(),
                    chapters.size(),
                    useChunk
                );

                if (!useChunk) {
                    boolean streamed = aiGatewayService.streamToEmitter(
                        promptConfig, inputText, analysisType, analysisTimeoutMillis, emitter, invocationHandle,
                        (em, aiResult) -> {
                            try {
                                String cacheKey = buildAnalysisCacheKey(
                                    promptConfig, request.getBookId(), analysisType, request.getChapterCount());
                                attachBookAnalysisMeta(aiResult, request.getChapterCount(), chapters.size());
                                Long resultId = saveAnalysisResult(
                                    request.getPlatform(), request.getBookId(), analysisType,
                                    request.getChapterCount(), promptConfig.getId(), aiResult);
                                AnalysisResultVO vo = new AnalysisResultVO();
                                vo.setId(resultId);
                                vo.setBookId(request.getBookId());
                                vo.setAnalysisType(analysisType);
                                vo.setModelName(aiResult.getModelName());
                                vo.setResultContent(aiResult.getContent());
                                vo.setResultJson(aiResult.getResultJson());
                                vo.setTokenUsed(aiResult.getTokenUsed());
                                crawlerCacheService.put(cacheKey, vo, ANALYSIS_TTL_SECONDS);
                                sendDoneEvent(em, vo);
                                em.complete();
                            } catch (Exception e) {
                                em.completeWithError(e);
                            }
                        },
                        err -> completeWithErrorEvent(emitter, traceId,
                            err instanceof Exception ex ? ex : new RuntimeException(err))
                    );
                    if (streamed) return; // 真流式已异步启动，退出当前线程
                }

                if (useChunk) {
                    AnalysisResultVO result = streamChunkedAnalysis(
                        emitter,
                        request,
                        promptConfig,
                        book,
                        chapters,
                        analysisType,
                        invocationHandle
                    );
                    ensureNotCancelled(invocationHandle);
                    sendDoneEvent(emitter, result);
                    emitter.complete();
                    return;
                }

                // 降级：chunk 分析或流式不可用 → 阻塞等全文再切割推送
                AnalysisResultVO result = analyze(analysisType, request);
                ensureNotCancelled(invocationHandle);
                sendDeltaEvents(emitter, result.getResultContent());
                sendDoneEvent(emitter, result);
                emitter.complete();
            } catch (AnalysisCancelledException ignored) {
                emitter.complete();
            } catch (Exception ex) {
                completeWithErrorEvent(emitter, traceId, ex);
            } finally {
                clearContext();
            }
        });
        return emitter;
    }

    public TrendAnalysisVO analyzeTrend(String platform, String channelCode, String boardCode) {
        RankBoardEntity board = crawlerRepository.findRankBoard(platform, channelCode, boardCode)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "rank board not found"));
        PromptConfigEntity promptConfig = resolveRuntimePromptConfig("theme");
        String cacheKey = buildTrendCacheKey(promptConfig, platform, channelCode, boardCode);
        TrendAnalysisVO cached = crawlerCacheService.get(cacheKey, new TypeReference<TrendAnalysisVO>() {
        });
        if (cached != null) {
            return cached;
        }

        List<RankSnapshotEntity> snapshots = crawlerRepository.findRecentBoardSnapshots(board.getId(), 3);
        if (snapshots.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "rank snapshot not found");
        }
        Long latestSnapshotId = snapshots.get(0).getId();
        List<AnalysisResultEntity> persistedCandidates = analysisRepository.findReusableBoardTrendCandidates(
            platform,
            channelCode,
            boardCode,
            promptConfig.getId(),
            latestSnapshotId,
            resolveAnalysisReuseTime(promptConfig),
            5
        );
        for (AnalysisResultEntity persisted : persistedCandidates) {
            if (!isReusableStructuredTrendResult(persisted, board, snapshots.size())) {
                continue;
            }
            TrendAnalysisVO persistedVo = toTrendAnalysisVO(persisted, board, snapshots.size());
            crawlerCacheService.put(cacheKey, persistedVo, ANALYSIS_TTL_SECONDS);
            return persistedVo;
        }

        Map<Long, List<CrawlRankEntity>> ranksBySnapshot = loadBoardSnapshotRanks(snapshots);
        AiInvokeResult aiResult = isLangGraphRuntimeEnabled()
            ? invokeLangGraphTrendAnalysis(promptConfig, board, snapshots, ranksBySnapshot)
            : invokeAi(promptConfig, buildTrendInputText(board, snapshots, ranksBySnapshot), "theme");
        aiResult.setResultJson(normalizeTrendResultJson(aiResult, board, snapshots.size()));
        Long anchorBookId = findAnchorBookId(ranksBySnapshot);
        saveAnalysisResult(
            platform,
            anchorBookId,
            channelCode,
            boardCode,
            latestSnapshotId,
            "theme",
            0,
            promptConfig.getId(),
            aiResult
        );

        TrendAnalysisVO vo = buildTrendAnalysisVO(board, aiResult, snapshots.size());
        crawlerCacheService.put(cacheKey, vo, ANALYSIS_TTL_SECONDS);
        return vo;
    }

    public SseEmitter streamTrend(TrendAnalysisRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AuthUser authUser = copyAuthUser(AuthUserHolder.get());
        String traceId = TraceIdHolder.get();
        streamTaskExecutor.execute(() -> {
            try {
                restoreContext(authUser, traceId);
                sendStartEvent(emitter, traceId, "theme");
                TrendAnalysisVO result;
                if (isLangGraphRuntimeEnabled()) {
                    result = streamLangGraphTrendAnalysis(emitter, request, traceId);
                } else {
                    result = analyzeTrend(
                        request.getPlatform(),
                        request.getChannelCode(),
                        request.getBoardCode()
                    );
                    sendDeltaEvents(emitter, result.getResultContent());
                }
                sendDoneEvent(emitter, result);
                emitter.complete();
            } catch (Exception ex) {
                completeWithErrorEvent(emitter, traceId, ex);
            } finally {
                clearContext();
            }
        });
        return emitter;
    }

    private boolean isLangGraphRuntimeEnabled() {
        return "langgraph".equalsIgnoreCase(
            systemConfigService.getValueOrDefault("analysis.runtime.mode", "legacy")
        );
    }

    private AiInvokeResult invokeLegacyBookAnalysis(PromptConfigEntity promptConfig,
                                                    CrawlBookEntity book,
                                                    List<ChapterVO> chapters,
                                                    String analysisType,
                                                    Integer requestedChapterCount) {
        String inputText = buildBookInputText(book, chapters);
        boolean useChunk = shouldUseChunkedAnalysis(promptConfig, analysisType, inputText, chapters);
        int analysisTimeoutMillis = resolveBookAnalysisTimeoutMillis(requestedChapterCount, chapters.size(), useChunk);
        return useChunk
            ? invokeChunkedAnalysis(promptConfig, book, chapters, analysisType, analysisTimeoutMillis)
            : invokeAi(promptConfig, inputText, analysisType, analysisTimeoutMillis);
    }

    private AiInvokeResult invokeLangGraphBookAnalysis(PromptConfigEntity promptConfig,
                                                       CrawlBookEntity book,
                                                       List<ChapterVO> chapters,
                                                       String analysisType) {
        return langGraphWorkerClient.run(buildLangGraphBookRequest(
            promptConfig,
            book.getPlatform(),
            book,
            chapters,
            analysisType,
            false
        ));
    }

    private AnalysisResultVO streamLangGraphBookAnalysis(SseEmitter emitter,
                                                         AnalysisRequest request,
                                                         PromptConfigEntity promptConfig,
                                                         CrawlBookEntity book,
                                                         List<ChapterVO> chapters,
                                                         String analysisType,
                                                         AiGatewayService.InvocationHandle invocationHandle) {
        AiInvokeResult aiResult = langGraphWorkerClient.stream(
            buildLangGraphBookRequest(promptConfig, request.getPlatform(), book, chapters, analysisType, true),
            delta -> {
                if (invocationHandle.isCancelled()) {
                    return;
                }
                try {
                    sendDeltaEvent(emitter, delta, null);
                } catch (IOException ex) {
                    invocationHandle.cancel();
                }
            },
            invocationHandle::isCancelled
        );
        if (aiResult == null || invocationHandle.isCancelled()) {
            throw new AnalysisCancelledException();
        }
        attachBookAnalysisMeta(aiResult, request.getChapterCount(), chapters.size());

        Long resultId = saveAnalysisResult(
            request.getPlatform(),
            request.getBookId(),
            analysisType,
            request.getChapterCount(),
            promptConfig.getId(),
            aiResult
        );

        AnalysisResultVO vo = new AnalysisResultVO();
        vo.setId(resultId);
        vo.setBookId(request.getBookId());
        vo.setAnalysisType(analysisType);
        vo.setModelName(aiResult.getModelName());
        vo.setResultContent(aiResult.getContent());
        vo.setResultJson(aiResult.getResultJson());
        vo.setTokenUsed(aiResult.getTokenUsed());
        crawlerCacheService.put(
            buildAnalysisCacheKey(promptConfig, request.getBookId(), analysisType, request.getChapterCount()),
            vo,
            ANALYSIS_TTL_SECONDS
        );
        return vo;
    }

    private AiInvokeResult invokeLangGraphTrendAnalysis(PromptConfigEntity promptConfig,
                                                        RankBoardEntity board,
                                                        List<RankSnapshotEntity> snapshots,
                                                        Map<Long, List<CrawlRankEntity>> ranksBySnapshot) {
        return langGraphWorkerClient.run(buildLangGraphTrendRequest(promptConfig, board, snapshots, ranksBySnapshot, false));
    }

    private TrendAnalysisVO streamLangGraphTrendAnalysis(SseEmitter emitter,
                                                         TrendAnalysisRequest request,
                                                         String traceId) {
        RankBoardEntity board = crawlerRepository.findRankBoard(
                request.getPlatform(),
                request.getChannelCode(),
                request.getBoardCode()
            )
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "rank board not found"));
        PromptConfigEntity promptConfig = resolveRuntimePromptConfig("theme");
        List<RankSnapshotEntity> snapshots = crawlerRepository.findRecentBoardSnapshots(board.getId(), 3);
        if (snapshots.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "rank snapshot not found");
        }
        Map<Long, List<CrawlRankEntity>> ranksBySnapshot = loadBoardSnapshotRanks(snapshots);
        AiInvokeResult aiResult = langGraphWorkerClient.stream(
            buildLangGraphTrendRequest(promptConfig, board, snapshots, ranksBySnapshot, true),
            delta -> {
                try {
                    sendDeltaEvent(emitter, delta, null);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            },
            () -> false
        );
        aiResult.setResultJson(normalizeTrendResultJson(aiResult, board, snapshots.size()));
        Long anchorBookId = findAnchorBookId(ranksBySnapshot);
        saveAnalysisResult(
            request.getPlatform(),
            anchorBookId,
            request.getChannelCode(),
            request.getBoardCode(),
            snapshots.get(0).getId(),
            "theme",
            0,
            promptConfig.getId(),
            aiResult
        );
        return buildTrendAnalysisVO(board, aiResult, snapshots.size());
    }

    private Map<String, Object> buildLangGraphBookRequest(PromptConfigEntity promptConfig,
                                                          String platform,
                                                          CrawlBookEntity book,
                                                          List<ChapterVO> chapters,
                                                          String analysisType,
                                                          boolean stream) {
        List<Map<String, Object>> chapterPayload = chapters.stream()
            .map(chapter -> Map.<String, Object>of(
                "chapterNo", chapter.getChapterNo(),
                "chapterTitle", firstNonBlank(chapter.getChapterTitle(), ""),
                "content", firstNonBlank(chapter.getContent(), "")
            ))
            .toList();
        Map<String, Object> bookPayload = new LinkedHashMap<>();
        bookPayload.put("platform", platform);
        bookPayload.put("bookId", book.getId());
        bookPayload.put("bookName", book.getBookName());
        bookPayload.put("author", book.getAuthor());
        bookPayload.put("intro", book.getIntro());
        bookPayload.put("bookUrl", book.getBookUrl());

        Map<String, Object> sourcePayload = new LinkedHashMap<>();
        sourcePayload.put("kind", "book_analysis");
        sourcePayload.put("platform", platform);
        sourcePayload.put("analysisType", analysisType);
        sourcePayload.put("inputText", buildBookInputText(book, chapters));
        sourcePayload.put("book", bookPayload);
        sourcePayload.put("chapters", chapterPayload);

        return buildLangGraphRunRequest(promptConfig, analysisType, sourcePayload, stream);
    }

    private Map<String, Object> buildLangGraphTrendRequest(PromptConfigEntity promptConfig,
                                                           RankBoardEntity board,
                                                           List<RankSnapshotEntity> snapshots,
                                                           Map<Long, List<CrawlRankEntity>> ranksBySnapshot,
                                                           boolean stream) {
        int trendRankLimit = resolveTrendSnapshotRankLimit(snapshots, ranksBySnapshot);
        List<Map<String, Object>> snapshotPayload = snapshots.stream()
            .map(snapshot -> {
                List<CrawlRankEntity> limitedRanks = selectTrendSnapshotRanks(snapshot, ranksBySnapshot, trendRankLimit);
                List<Map<String, Object>> rankPayload = limitedRanks.stream()
                    .map(item -> {
                        Map<String, Object> rank = new LinkedHashMap<>();
                        rank.put("rankNo", item.getRankNo());
                        rank.put("bookId", item.getBookId());
                        rank.put("bookName", item.getBookName());
                        rank.put("author", item.getAuthor());
                        rank.put("intro", item.getIntro());
                        return rank;
                    })
                    .toList();
                Map<String, Object> snapshotMap = new LinkedHashMap<>();
                snapshotMap.put("snapshotId", snapshot.getId());
                snapshotMap.put("snapshotTime", snapshot.getSnapshotTime().format(DATE_TIME_FORMATTER));
                snapshotMap.put("recordCount", rankPayload.size());
                snapshotMap.put("ranks", rankPayload);
                return snapshotMap;
            })
            .toList();

        Map<String, Object> sourcePayload = new LinkedHashMap<>();
        sourcePayload.put("kind", "trend_analysis");
        sourcePayload.put("analysisType", "theme");
        sourcePayload.put("platform", board.getPlatform());
        sourcePayload.put("channelCode", board.getChannelCode());
        sourcePayload.put("boardCode", board.getBoardCode());
        sourcePayload.put("boardName", board.getBoardName());
        sourcePayload.put("inputText", buildTrendInputText(board, snapshots, ranksBySnapshot));
        sourcePayload.put("snapshots", snapshotPayload);

        return buildLangGraphRunRequest(promptConfig, "theme", sourcePayload, stream);
    }

    private Map<String, Object> buildLangGraphRunRequest(PromptConfigEntity promptConfig,
                                                         String analysisType,
                                                         Map<String, Object> sourcePayload,
                                                         boolean stream) {
        AiModelRegistryModelVO runtimeModel = resolveLangGraphRuntimeModel(promptConfig);
        Map<String, Object> promptPayload = new LinkedHashMap<>();
        promptPayload.put("promptType", promptConfig.getPromptType());
        promptPayload.put("promptName", promptConfig.getPromptName());
        promptPayload.put("promptContent", promptConfig.getPromptContent());
        promptPayload.put("inputJsonSchema", promptConfig.getInputJsonSchema());
        promptPayload.put("inputExampleJson", promptConfig.getInputExampleJson());
        promptPayload.put("modelKey", runtimeModel == null ? null : runtimeModel.getModelKey());
        promptPayload.put("displayName", runtimeModel == null ? null : runtimeModel.getDisplayName());
        promptPayload.put("providerType", runtimeModel == null ? null : runtimeModel.getProviderType());
        promptPayload.put("modelName", runtimeModel == null
            ? promptConfig.getModelName()
            : firstNonBlank(runtimeModel.getModelName(), runtimeModel.getModelKey()));
        promptPayload.put("baseUrl", runtimeModel == null ? null : runtimeModel.getBaseUrl());
        promptPayload.put("apiKey", runtimeModel == null ? null : runtimeModel.getApiKey());
        Integer resolvedMaxTokens = resolveLangGraphMaxTokens(promptConfig, runtimeModel, analysisType);
        promptPayload.put("temperature", promptConfig.getTemperature() != null
            ? promptConfig.getTemperature()
            : runtimeModel == null ? null : runtimeModel.getDefaultTemperature());
        promptPayload.put("maxTokens", resolvedMaxTokens);
        promptPayload.put("temperatureSpecJson", runtimeModel == null ? null : runtimeModel.getTemperatureSpecJson());
        promptPayload.put("outputJsonSchema", promptConfig.getOutputJsonSchema());
        promptPayload.put("outputExampleJson", promptConfig.getOutputExampleJson());
        promptPayload.put("postProcessType", promptConfig.getPostProcessType());
        promptPayload.put("parseConfigJson", promptConfig.getParseConfigJson());

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeoutMillis", resolveLangGraphTimeoutMillis(analysisType));
        limits.put("chunkMaxInputTokens", resolveChunkMaxInputTokens(promptConfig));
        limits.put("chunkTargetInputTokens", resolveChunkTargetInputTokens(promptConfig));
        limits.put("chunkParallelism", Math.min(2, resolveChunkParallelism()));

        Map<String, Object> contextMeta = new LinkedHashMap<>();
        contextMeta.put("runtimeMode", "langgraph");
        contextMeta.put("traceId", TraceIdHolder.get());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskId", java.util.UUID.randomUUID().toString());
        request.put("traceId", TraceIdHolder.get());
        request.put("agentType", resolveLangGraphAgentType(analysisType));
        request.put("stream", stream);
        request.put("promptConfig", promptPayload);
        request.put("sourcePayload", sourcePayload);
        request.put("limits", limits);
        request.put("contextMeta", contextMeta);
        return request;
    }

    private int resolveLangGraphTimeoutMillis(String analysisType) {
        int configuredTimeout = systemConfigService.getIntValueOrDefault("ai.timeout.millis", 15000);
        if ("theme".equalsIgnoreCase(analysisType)) {
            return Math.max(configuredTimeout, TREND_LANGGRAPH_TIMEOUT_MILLIS);
        }
        return configuredTimeout;
    }

    private Integer resolveLangGraphMaxTokens(PromptConfigEntity promptConfig,
                                              AiModelRegistryModelVO runtimeModel,
                                              String analysisType) {
        Integer promptMaxTokens = promptConfig == null ? null : promptConfig.getMaxTokens();
        Integer runtimeMaxTokens = runtimeModel == null ? null : runtimeModel.getMaxTokens();
        if ("theme".equalsIgnoreCase(analysisType)) {
            return maxNullable(promptMaxTokens, runtimeMaxTokens);
        }
        return promptMaxTokens != null ? promptMaxTokens : runtimeMaxTokens;
    }

    private Integer maxNullable(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private AiModelRegistryModelVO resolveLangGraphRuntimeModel(PromptConfigEntity promptConfig) {
        return systemConfigService.resolveEnabledModel(
                resolveUserPreferredModelKey(),
                resolvePromptConfiguredModel(promptConfig)
            )
            .orElse(null);
    }

    private String resolveUserPreferredModelKey() {
        try {
            AuthUser authUser = AuthUserHolder.get();
            if (authUser == null) {
                return null;
            }
            return userConfigService.getValueForUser(authUser.getUserId(), "ai.preferred-model");
        } catch (Exception ignored) {
            return null;
        }
    }

    private PromptConfigEntity resolveRuntimePromptConfig(String analysisType) {
        return promptConfigService.resolveRuntimePromptConfig(
            analysisType,
            resolveUserPreferredModelKey(),
            systemConfigService.getModelRegistry().getModels()
        );
    }

    private String resolveRuntimeModelCacheToken(PromptConfigEntity promptConfig) {
        return systemConfigService.resolveEnabledModel(
                resolveUserPreferredModelKey(),
                resolvePromptConfiguredModel(promptConfig)
            )
            .map(model -> {
                String modelName = model.getModelName();
                if (modelName != null && !modelName.isBlank()) {
                    return modelName;
                }
                return model.getModelKey();
            })
            .orElseGet(() -> {
                String modelName = resolvePromptConfiguredModel(promptConfig);
                return modelName == null || modelName.isBlank() ? "default-model" : modelName;
            });
    }

    private String resolvePromptConfiguredModel(PromptConfigEntity promptConfig) {
        if (promptConfig == null) {
            return null;
        }
        String modelName = promptConfig.getModelName();
        if (modelName == null || modelName.isBlank() || "dify".equalsIgnoreCase(modelName)) {
            return null;
        }
        return modelName;
    }

    private String resolveLangGraphAgentType(String analysisType) {
        if ("theme".equals(analysisType)) {
            return "trend_theme";
        }
        return analysisType;
    }

    private String buildAnalysisCacheKey(PromptConfigEntity promptConfig,
                                         Long bookId,
                                         String analysisType,
                                         Integer chapterCount) {
        return "analysis:" + bookId + ":" + analysisType + ":" + chapterCount + ":"
            + resolveUserPreferredModelKey() + ":"
            + resolveRuntimeModelCacheToken(promptConfig) + ":"
            + buildPromptSignature(promptConfig);
    }

    private String buildTrendCacheKey(PromptConfigEntity promptConfig,
                                      String platform,
                                      String channelCode,
                                      String boardCode) {
        return "analysis:trend:" + platform + ":" + channelCode + ":" + boardCode + ":"
            + resolveUserPreferredModelKey() + ":"
            + resolveRuntimeModelCacheToken(promptConfig) + ":"
            + buildPromptSignature(promptConfig);
    }

    private boolean shouldUseChunkedAnalysis(PromptConfigEntity promptConfig,
                                             String analysisType,
                                             String inputText,
                                             List<ChapterVO> chapters) {
        if (!supportsChunkedAnalysis(analysisType) || chapters == null || chapters.size() <= 1) {
            return false;
        }
        return aiGatewayService.estimatePromptTokens(promptConfig, inputText, analysisType)
            > resolveChunkMaxInputTokens(promptConfig);
    }

    private boolean supportsChunkedAnalysis(String analysisType) {
        return "deconstruct".equals(analysisType)
            || "structure".equals(analysisType)
            || "plot".equals(analysisType);
    }

    private int resolveChunkMaxInputTokens(PromptConfigEntity promptConfig) {
        int configured = Math.max(
            1000,
            systemConfigService.getIntValueOrDefault("analysis.chunk.max-input-tokens", DEFAULT_CHUNK_MAX_INPUT_TOKENS)
        );
        if (shouldUseDeepSeekChunkDefaults(promptConfig) && isLegacyOrSystemChunkDefault(configured, DEFAULT_CHUNK_MAX_INPUT_TOKENS, SYSTEM_DEFAULT_CHUNK_MAX_INPUT_TOKENS)) {
            return DEEPSEEK_CHUNK_MAX_INPUT_TOKENS;
        }
        return configured;
    }

    private int resolveChunkTargetInputTokens(PromptConfigEntity promptConfig) {
        int configured = Math.max(
            1000,
            systemConfigService.getIntValueOrDefault("analysis.chunk.target-input-tokens", DEFAULT_CHUNK_TARGET_INPUT_TOKENS)
        );
        int effective = shouldUseDeepSeekChunkDefaults(promptConfig)
            && isLegacyOrSystemChunkDefault(configured, DEFAULT_CHUNK_TARGET_INPUT_TOKENS, SYSTEM_DEFAULT_CHUNK_TARGET_INPUT_TOKENS)
            ? DEEPSEEK_CHUNK_TARGET_INPUT_TOKENS
            : configured;
        return Math.min(effective, resolveChunkMaxInputTokens(promptConfig));
    }

    private int resolveChunkParallelism() {
        return Math.max(
            1,
            Math.min(
                6,
                systemConfigService.getIntValueOrDefault("analysis.chunk.parallelism", DEFAULT_CHUNK_PARALLELISM)
            )
        );
    }

    private boolean shouldUseDeepSeekChunkDefaults(PromptConfigEntity promptConfig) {
        String modelName = promptConfig == null ? null : promptConfig.getModelName();
        if (modelName == null || modelName.isBlank() || "dify".equalsIgnoreCase(modelName)) {
            modelName = systemConfigService.getValueOrDefault(
                "ai.openai-compatible.default-model",
                "deepseek-chat"
            );
        }
        return modelName != null && modelName.toLowerCase(Locale.ROOT).contains("deepseek");
    }

    private boolean isLegacyOrSystemChunkDefault(int configured, int legacyDefault, int systemDefault) {
        return configured == legacyDefault || configured == systemDefault;
    }

    private LocalDateTime resolveAnalysisReuseTime(PromptConfigEntity promptConfig) {
        LocalDateTime ttlStart = LocalDateTime.now().minusSeconds(ANALYSIS_TTL_SECONDS);
        LocalDateTime promptUpdatedAt = promptConfig.getUpdateTime();
        if (promptUpdatedAt == null || promptUpdatedAt.isBefore(ttlStart)) {
            return ttlStart;
        }
        return promptUpdatedAt;
    }

    private AuthUser copyAuthUser(AuthUser authUser) {
        if (authUser == null) {
            return null;
        }
        return AuthUser.of(authUser.getUserId(), authUser.getUsername(), authUser.getRoles());
    }

    private void restoreContext(AuthUser authUser, String traceId) {
        if (authUser != null) {
            AuthUserHolder.set(authUser);
        }
        if (traceId != null && !traceId.isBlank()) {
            TraceIdHolder.set(traceId);
        }
    }

    private void clearContext() {
        AuthUserHolder.clear();
        TraceIdHolder.clear();
    }

    private void registerEmitterLifecycle(SseEmitter emitter, AiGatewayService.InvocationHandle invocationHandle) {
        emitter.onCompletion(invocationHandle::cancel);
        emitter.onTimeout(invocationHandle::cancel);
        emitter.onError(error -> invocationHandle.cancel());
    }

    private void sendStartEvent(SseEmitter emitter, String traceId, String analysisType) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "start");
        payload.put("traceId", traceId);
        payload.put("analysisType", analysisType);
        sendEvent(emitter, "start", payload);
    }

    private void sendDeltaEvents(SseEmitter emitter, String content) throws IOException {
        List<String> chunks = splitContent(content);
        for (int i = 0; i < chunks.size(); i++) {
            sendDeltaEvent(emitter, chunks.get(i), i);
        }
    }

    private void sendDeltaEvent(SseEmitter emitter, String delta, Integer chunkIndex) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "delta");
        payload.put("delta", delta);
        if (chunkIndex != null) {
            payload.put("chunkIndex", chunkIndex);
        }
        sendEvent(emitter, "delta", payload);
    }

    private void sendDoneEvent(SseEmitter emitter, Object data) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "done");
        payload.put("data", data);
        sendEvent(emitter, "done", payload);
    }

    private void completeWithErrorEvent(SseEmitter emitter, String traceId, Exception ex) {
        ResultCode resultCode = ResultCode.INTERNAL_ERROR;
        String message = ResultCode.INTERNAL_ERROR.getMessage();
        if (ex instanceof BusinessException businessException) {
            resultCode = businessException.getResultCode();
            message = businessException.getMessage();
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "error");
            payload.put("code", resultCode.getCode());
            payload.put("message", message);
            if (traceId != null && !traceId.isBlank()) {
                payload.put("traceId", traceId);
            }
            sendEvent(emitter, "error", payload);
            emitter.complete();
        } catch (Exception sendException) {
            emitter.completeWithError(sendException);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) throws IOException {
        emitter.send(SseEmitter.event()
            .name(eventName)
            .data(payload, MediaType.APPLICATION_JSON));
    }

    private List<String> splitContent(String content) {
        String safeContent = content == null ? "" : content;
        if (safeContent.isEmpty()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < safeContent.length(); start += STREAM_CHUNK_SIZE) {
            int end = Math.min(safeContent.length(), start + STREAM_CHUNK_SIZE);
            chunks.add(safeContent.substring(start, end));
        }
        return chunks;
    }

    private String buildPromptSignature(PromptConfigEntity promptConfig) {
        return Integer.toHexString(Objects.hash(
            promptConfig.getId(),
            promptConfig.getPromptName(),
            promptConfig.getPromptContent(),
            promptConfig.getModelName(),
            promptConfig.getTemperature(),
            promptConfig.getMaxTokens(),
            promptConfig.getDifyWorkflowId(),
            promptConfig.getDifyApiKeyRef(),
            promptConfig.getUpdateTime()
        ));
    }

    private String buildBookInputText(CrawlBookEntity book, List<ChapterVO> chapters) {
        StringBuilder builder = new StringBuilder();
        builder.append("Book: ").append(book.getBookName()).append("\n");
        builder.append("Author: ").append(book.getAuthor()).append("\n");
        builder.append("Intro: ").append(book.getIntro()).append("\n");
        for (ChapterVO chapter : chapters) {
            builder.append("[").append(chapter.getChapterTitle()).append("] ")
                .append(chapter.getContent()).append("\n");
        }
        return builder.toString();
    }

    private void attachBookAnalysisMeta(AiInvokeResult aiResult,
                                        Integer requestedChapterCount,
                                        int actualChapterCount) {
        if (aiResult == null) {
            return;
        }
        Map<String, Object> resultJson = new LinkedHashMap<>(
            aiResult.getResultJson() == null ? Map.of() : aiResult.getResultJson()
        );
        if (requestedChapterCount != null && requestedChapterCount > 0) {
            resultJson.put("requestedChapterCount", requestedChapterCount);
        }
        resultJson.put("actualChapterCount", actualChapterCount);
        resultJson.put("inputChapterCount", actualChapterCount);
        resultJson.put("chapterFetchDegraded",
            requestedChapterCount != null && requestedChapterCount > actualChapterCount);
        aiResult.setResultJson(resultJson);
    }

    private int resolveBookAnalysisTimeoutMillis(Integer requestedChapterCount,
                                                 int actualChapterCount,
                                                 boolean useChunking) {
        int configuredTimeout = systemConfigService.getIntValueOrDefault("ai.timeout.millis", 15000);
        int effectiveChapterCount = Math.max(requestedChapterCount == null ? 0 : requestedChapterCount, actualChapterCount);
        if (useChunking || effectiveChapterCount >= 10) {
            return Math.max(configuredTimeout, LARGE_BOOK_ANALYSIS_TIMEOUT_MILLIS);
        }
        return configuredTimeout;
    }

    private AiInvokeResult invokeChunkedAnalysis(PromptConfigEntity promptConfig,
                                                 CrawlBookEntity book,
                                                 List<ChapterVO> chapters,
                                                 String analysisType) {
        return invokeChunkedAnalysis(
            promptConfig,
            book,
            chapters,
            analysisType,
            LARGE_BOOK_ANALYSIS_TIMEOUT_MILLIS,
            null,
            null
        );
    }

    private AiInvokeResult invokeChunkedAnalysis(PromptConfigEntity promptConfig,
                                                 CrawlBookEntity book,
                                                 List<ChapterVO> chapters,
                                                 String analysisType,
                                                 int timeoutMillis) {
        return invokeChunkedAnalysis(promptConfig, book, chapters, analysisType, timeoutMillis, null, null);
    }

    private AiInvokeResult invokeChunkedAnalysis(PromptConfigEntity promptConfig,
                                                 CrawlBookEntity book,
                                                 List<ChapterVO> chapters,
                                                 String analysisType,
                                                 java.util.function.Consumer<String> progressListener) {
        return invokeChunkedAnalysis(
            promptConfig,
            book,
            chapters,
            analysisType,
            LARGE_BOOK_ANALYSIS_TIMEOUT_MILLIS,
            progressListener,
            null
        );
    }

    private AiInvokeResult invokeChunkedAnalysis(PromptConfigEntity promptConfig,
                                                 CrawlBookEntity book,
                                                 List<ChapterVO> chapters,
                                                 String analysisType,
                                                 int timeoutMillis,
                                                 java.util.function.Consumer<String> progressListener,
                                                 AiGatewayService.InvocationHandle invocationHandle) {
        ensureNotCancelled(invocationHandle);
        List<List<ChapterVO>> chunks = splitChaptersForChunkedAnalysis(promptConfig, book, chapters, analysisType);
        if (chunks.size() <= 1) {
            ensureNotCancelled(invocationHandle);
            return invokeAi(promptConfig, buildBookInputText(book, chapters), analysisType, timeoutMillis);
        }

        List<ChunkAnalysisOutcome> outcomes = invocationHandle == null
            ? analyzeChunks(promptConfig, book, chunks, analysisType, timeoutMillis, progressListener)
            : analyzeChunksCancellable(promptConfig, book, chunks, analysisType, timeoutMillis, progressListener, invocationHandle);
        List<String> chunkOutputs = new ArrayList<>();
        int tokenUsed = 0;
        for (ChunkAnalysisOutcome outcome : outcomes) {
            ensureNotCancelled(invocationHandle);
            tokenUsed += Math.max(0, outcome.result().getTokenUsed());
            chunkOutputs.add(buildChunkResultText(outcome.index() + 1, outcome.chunk(), outcome.result()));
        }

        emitChunkProgress(progressListener, "\n[chunk-progress] 分段分析已完成，正在汇总最终结果...\n");

        PromptConfigEntity mergePrompt = copyPromptConfig(
            promptConfig,
            buildChunkMergePromptTemplate(promptConfig, analysisType)
        );
        AiInvokeResult mergedResult = aiGatewayService.analyze(
            mergePrompt,
            buildChunkMergeInput(book, chunkOutputs),
            analysisType,
            timeoutMillis
        );
        ensureNotCancelled(invocationHandle);
        tokenUsed += Math.max(0, mergedResult.getTokenUsed());

        Map<String, Object> resultJson = new HashMap<>(mergedResult.getResultJson() == null ? Map.of() : mergedResult.getResultJson());
        resultJson.put("analysisMode", "chunk_merge");
        resultJson.put("segmentCount", chunks.size());
        resultJson.put("inputChapterCount", chapters.size());
        mergedResult.setResultJson(resultJson);
        mergedResult.setTokenUsed(tokenUsed);
        return mergedResult;
    }

    private List<ChunkAnalysisOutcome> analyzeChunks(PromptConfigEntity promptConfig,
                                                     CrawlBookEntity book,
                                                     List<List<ChapterVO>> chunks,
                                                     String analysisType,
                                                     int timeoutMillis,
                                                     java.util.function.Consumer<String> progressListener) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        ExecutorService executor = Executors.newFixedThreadPool(resolveChunkParallelism());
        ExecutorCompletionService<ChunkAnalysisOutcome> completionService = new ExecutorCompletionService<>(executor);
        List<ChunkAnalysisOutcome> outcomes = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        try {
            for (int index = 0; index < chunks.size(); index++) {
                final int chunkIndex = index;
                final List<ChapterVO> chunk = chunks.get(index);
                emitChunkProgress(progressListener, buildChunkProgressMessage(chunkIndex + 1, chunks.size(), chunk, "正在分析"));
                completionService.submit(() -> analyzeSingleChunk(
                    promptConfig,
                    book,
                    chunks.size(),
                    analysisType,
                    timeoutMillis,
                    chunkIndex,
                    chunk
                ));
            }

            for (int completed = 0; completed < chunks.size(); completed++) {
                ChunkAnalysisOutcome outcome = completionService.take().get();
                outcomes.set(outcome.index(), outcome);
                emitChunkProgress(
                    progressListener,
                    buildChunkProgressMessage(outcome.index() + 1, chunks.size(), outcome.chunk(), "已完成")
                );
            }
            return outcomes;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("chunk analysis interrupted", ex);
        } catch (ExecutionException ex) {
            throw unwrapChunkExecutionException(ex);
        } finally {
            executor.shutdownNow();
        }
    }

    private ChunkAnalysisOutcome analyzeSingleChunk(PromptConfigEntity promptConfig,
                                                    CrawlBookEntity book,
                                                    int chunkCount,
                                                    String analysisType,
                                                    int timeoutMillis,
                                                    int chunkIndex,
                                                    List<ChapterVO> chunk) {
        String chunkInput = buildBookInputText(book, chunk);
        PromptConfigEntity chunkPrompt = copyPromptConfig(
            promptConfig,
            buildChunkPromptTemplate(promptConfig, analysisType, chunkIndex + 1, chunkCount)
        );
        AiInvokeResult chunkResult = aiGatewayService.analyze(chunkPrompt, chunkInput, analysisType, timeoutMillis);
        return new ChunkAnalysisOutcome(chunkIndex, chunk, chunkResult);
    }

    private List<ChunkAnalysisOutcome> analyzeChunksCancellable(PromptConfigEntity promptConfig,
                                                                CrawlBookEntity book,
                                                                List<List<ChapterVO>> chunks,
                                                                String analysisType,
                                                                int timeoutMillis,
                                                                java.util.function.Consumer<String> progressListener,
                                                                AiGatewayService.InvocationHandle invocationHandle) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        ExecutorService executor = Executors.newFixedThreadPool(resolveChunkParallelism());
        ExecutorCompletionService<ChunkAnalysisOutcome> completionService = new ExecutorCompletionService<>(executor);
        List<ChunkAnalysisOutcome> outcomes = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        try {
            int submitted = 0;
            int initialParallelism = Math.min(resolveChunkParallelism(), chunks.size());
            for (; submitted < initialParallelism; submitted++) {
                submitChunkAnalysis(
                    completionService,
                    promptConfig,
                    book,
                    chunks,
                    analysisType,
                    timeoutMillis,
                    progressListener,
                    invocationHandle,
                    submitted
                );
            }

            for (int completed = 0; completed < chunks.size(); completed++) {
                ChunkAnalysisOutcome outcome = awaitChunkOutcome(completionService, invocationHandle);
                ensureNotCancelled(invocationHandle);
                outcomes.set(outcome.index(), outcome);
                emitChunkProgress(
                    progressListener,
                    buildChunkProgressMessage(outcome.index() + 1, chunks.size(), outcome.chunk(), "已完成")
                );
                if (submitted < chunks.size()) {
                    submitChunkAnalysis(
                        completionService,
                        promptConfig,
                        book,
                        chunks,
                        analysisType,
                        timeoutMillis,
                        progressListener,
                        invocationHandle,
                        submitted
                    );
                    submitted++;
                }
            }
            return outcomes;
        } catch (AnalysisCancelledException ex) {
            executor.shutdownNow();
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("chunk analysis interrupted", ex);
        } catch (ExecutionException ex) {
            throw unwrapChunkExecutionException(ex);
        } finally {
            executor.shutdownNow();
        }
    }

    private ChunkAnalysisOutcome analyzeSingleChunk(PromptConfigEntity promptConfig,
                                                    CrawlBookEntity book,
                                                    int chunkCount,
                                                    String analysisType,
                                                    int timeoutMillis,
                                                    int chunkIndex,
                                                    List<ChapterVO> chunk,
                                                    AiGatewayService.InvocationHandle invocationHandle) {
        ensureNotCancelled(invocationHandle);
        ChunkAnalysisOutcome outcome = analyzeSingleChunk(
            promptConfig,
            book,
            chunkCount,
            analysisType,
            timeoutMillis,
            chunkIndex,
            chunk
        );
        ensureNotCancelled(invocationHandle);
        return outcome;
    }

    private RuntimeException unwrapChunkExecutionException(ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException("chunk analysis failed", cause == null ? ex : cause);
    }

    private List<List<ChapterVO>> splitChaptersForChunkedAnalysis(PromptConfigEntity promptConfig,
                                                                  CrawlBookEntity book,
                                                                  List<ChapterVO> chapters,
                                                                  String analysisType) {
        if (chapters != null && chapters.size() >= LARGE_BOOK_FORCE_CHUNK_CHAPTER_COUNT) {
            return splitChaptersByFixedSize(chapters, LARGE_BOOK_FORCE_CHUNK_SEGMENT_SIZE);
        }
        List<List<ChapterVO>> result = new ArrayList<>();
        List<ChapterVO> current = new ArrayList<>();
        int targetTokens = resolveChunkTargetInputTokens(promptConfig);
        for (ChapterVO chapter : chapters) {
            if (chapter == null) {
                continue;
            }
            List<ChapterVO> candidate = new ArrayList<>(current);
            candidate.add(chapter);
            String candidateInput = buildBookInputText(book, candidate);
            int estimatedTokens = aiGatewayService.estimatePromptTokens(promptConfig, candidateInput, analysisType);
            if (!current.isEmpty() && estimatedTokens > targetTokens) {
                result.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(chapter);
        }
        if (!current.isEmpty()) {
            result.add(List.copyOf(current));
        }
        return result;
    }

    private List<List<ChapterVO>> splitChaptersByFixedSize(List<ChapterVO> chapters, int segmentSize) {
        List<List<ChapterVO>> result = new ArrayList<>();
        if (chapters == null || chapters.isEmpty()) {
            return result;
        }
        int safeSegmentSize = Math.max(1, segmentSize);
        List<ChapterVO> current = new ArrayList<>(safeSegmentSize);
        for (ChapterVO chapter : chapters) {
            if (chapter == null) {
                continue;
            }
            current.add(chapter);
            if (current.size() >= safeSegmentSize) {
                result.add(List.copyOf(current));
                current = new ArrayList<>(safeSegmentSize);
            }
        }
        if (!current.isEmpty()) {
            result.add(List.copyOf(current));
        }
        return result;
    }

    private PromptConfigEntity copyPromptConfig(PromptConfigEntity source, String promptContent) {
        PromptConfigEntity target = new PromptConfigEntity();
        target.setId(source.getId());
        target.setPromptType(source.getPromptType());
        target.setPromptName(source.getPromptName());
        target.setPromptContent(promptContent);
        target.setModelName(source.getModelName());
        target.setTemperature(source.getTemperature());
        target.setMaxTokens(source.getMaxTokens());
        target.setDifyWorkflowId(source.getDifyWorkflowId());
        target.setDifyApiKeyRef(source.getDifyApiKeyRef());
        target.setInputJsonSchema(source.getInputJsonSchema());
        target.setInputExampleJson(source.getInputExampleJson());
        target.setOutputJsonSchema(source.getOutputJsonSchema());
        target.setOutputExampleJson(source.getOutputExampleJson());
        target.setPostProcessType(source.getPostProcessType());
        target.setParseConfigJson(source.getParseConfigJson());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private String buildChunkPromptTemplate(PromptConfigEntity promptConfig,
                                            String analysisType,
                                            int chunkIndex,
                                            int chunkCount) {
        String promptInstruction = aiGatewayService.resolvePromptTemplate(promptConfig, analysisType).replace("{{content}}", "").trim();
        return "你正在进行长篇小说分段分析。\n"
            + "当前是第 " + chunkIndex + "/" + chunkCount + " 段，请只基于当前分段正文输出局部分析，保留关键人物、冲突、伏笔、节奏与重要细节。\n"
            + "原始分析要求：\n"
            + promptInstruction
            + "\n\n{{content}}";
    }

    private String buildChunkMergePromptTemplate(PromptConfigEntity promptConfig, String analysisType) {
        String promptInstruction = aiGatewayService.resolvePromptTemplate(promptConfig, analysisType).replace("{{content}}", "").trim();
        return "你正在整合同一本小说的多段局部分析结果。\n"
            + "请去重、补全跨章节关系，保持与原始分析要求一致的输出风格，输出一份最终结论。\n"
            + "不要遗漏人物关系、冲突升级、章节节奏和关键卖点。\n"
            + "原始分析要求：\n"
            + promptInstruction
            + "\n\n{{content}}";
    }

    private String buildChunkResultText(int chunkIndex, List<ChapterVO> chunk, AiInvokeResult chunkResult) {
        String firstTitle = chunk.isEmpty() ? "unknown" : chunk.get(0).getChapterTitle();
        String lastTitle = chunk.isEmpty() ? "unknown" : chunk.get(chunk.size() - 1).getChapterTitle();
        return "## 分段 " + chunkIndex + "\n"
            + "范围：" + firstTitle + " -> " + lastTitle + "\n"
            + chunkResult.getContent();
    }

    private String buildChunkMergeInput(CrawlBookEntity book, List<String> chunkOutputs) {
        StringBuilder builder = new StringBuilder();
        builder.append("Book: ").append(book.getBookName()).append("\n");
        builder.append("Author: ").append(book.getAuthor()).append("\n");
        builder.append("Intro: ").append(book.getIntro()).append("\n\n");
        for (String chunkOutput : chunkOutputs) {
            builder.append(chunkOutput).append("\n\n");
        }
        return builder.toString();
    }

    private AnalysisResultVO streamChunkedAnalysis(SseEmitter emitter,
                                                   AnalysisRequest request,
                                                   PromptConfigEntity promptConfig,
                                                   CrawlBookEntity book,
                                                   List<ChapterVO> chapters,
                                                   String analysisType,
                                                   AiGatewayService.InvocationHandle invocationHandle) throws IOException {
        int analysisTimeoutMillis = resolveBookAnalysisTimeoutMillis(
            request.getChapterCount(),
            chapters.size(),
            true
        );
        AiInvokeResult aiResult = invokeChunkedAnalysis(
            promptConfig,
            book,
            chapters,
            analysisType,
            analysisTimeoutMillis,
            progress -> {
                try {
                    ensureNotCancelled(invocationHandle);
                    sendDeltaEvent(emitter, progress, null);
                } catch (IOException ex) {
                    invocationHandle.cancel();
                    throw new AnalysisCancelledException();
                }
            },
            invocationHandle
        );
        ensureNotCancelled(invocationHandle);
        attachBookAnalysisMeta(aiResult, request.getChapterCount(), chapters.size());

        Long resultId = saveAnalysisResult(
            request.getPlatform(),
            request.getBookId(),
            analysisType,
            request.getChapterCount(),
            promptConfig.getId(),
            aiResult
        );

        AnalysisResultVO vo = new AnalysisResultVO();
        vo.setId(resultId);
        vo.setBookId(request.getBookId());
        vo.setAnalysisType(analysisType);
        vo.setModelName(aiResult.getModelName());
        vo.setResultContent(aiResult.getContent());
        vo.setResultJson(aiResult.getResultJson());
        vo.setTokenUsed(aiResult.getTokenUsed());
        crawlerCacheService.put(
            buildAnalysisCacheKey(promptConfig, request.getBookId(), analysisType, request.getChapterCount()),
            vo,
            ANALYSIS_TTL_SECONDS
        );
        return vo;
    }

    private void emitChunkProgress(java.util.function.Consumer<String> progressListener, String message) {
        if (progressListener == null || message == null || message.isBlank()) {
            return;
        }
        progressListener.accept(message);
    }

    private String buildChunkProgressMessage(int chunkIndex,
                                             int chunkCount,
                                             List<ChapterVO> chunk,
                                             String status) {
        String firstTitle = chunk.isEmpty() ? "unknown" : chunk.get(0).getChapterTitle();
        String lastTitle = chunk.isEmpty() ? "unknown" : chunk.get(chunk.size() - 1).getChapterTitle();
        return "\n[chunk-progress] 第 " + chunkIndex + "/" + chunkCount + " 段" + status
            + "（" + firstTitle + " ~ " + lastTitle + "）\n";
    }

    private List<ChapterVO> loadAnalysisChapters(AnalysisRequest request) {
        CrawlerChapterRequest chapterRequest = new CrawlerChapterRequest();
        chapterRequest.setPlatform(request.getPlatform());
        chapterRequest.setBookId(request.getBookId());
        chapterRequest.setChapterCount(request.getChapterCount());
        return crawlerService.getChapters(chapterRequest);
    }

    private Map<Long, List<CrawlRankEntity>> loadBoardSnapshotRanks(List<RankSnapshotEntity> snapshots) {
        return crawlerRepository.findRanksBySnapshotIds(
            snapshots.stream().map(RankSnapshotEntity::getId).toList()
        ).stream().collect(java.util.stream.Collectors.groupingBy(
            CrawlRankEntity::getSnapshotId,
            LinkedHashMap::new,
            java.util.stream.Collectors.toList()
        ));
    }

    private String buildTrendInputText(RankBoardEntity board,
                                       List<RankSnapshotEntity> snapshots,
                                       Map<Long, List<CrawlRankEntity>> ranksBySnapshot) {
        StringBuilder builder = new StringBuilder();
        int snapshotCount = snapshots.size();
        int trendRankLimit = resolveTrendSnapshotRankLimit(snapshots, ranksBySnapshot);
        builder.append("Platform: ").append(board.getPlatform()).append("\n");
        builder.append("Channel: ").append(board.getChannelCode()).append("\n");
        builder.append("Board: ").append(board.getBoardCode()).append(" / ").append(board.getBoardName()).append("\n");
        builder.append("Task: Analyze this exact rank board using the latest available captured snapshots (")
            .append(snapshotCount)
            .append(" in total). If fewer than three snapshots are available, use the available data directly instead of refusing. ")
            .append("Return structured JSON with summary, boardSummary, trendPreview, historicalWordCloud, themeDistribution, ")
            .append("themeTable, hotBooks, systemArchetypes, microInnovationSignals, insightCards, and snapshotComparisons.\n\n");
        int index = 1;
        for (RankSnapshotEntity snapshot : snapshots) {
            List<CrawlRankEntity> items = selectTrendSnapshotRanks(snapshot, ranksBySnapshot, trendRankLimit);
            builder.append("Snapshot ").append(index++).append(" @ ")
                .append(snapshot.getSnapshotTime().format(DATE_TIME_FORMATTER))
                .append(" (records=").append(items.size()).append(")\n");
            for (CrawlRankEntity item : items) {
                builder.append("- #").append(item.getRankNo())
                    .append(" ").append(item.getBookName())
                    .append(" / ").append(item.getAuthor())
                    .append(" / ").append(compactTrendIntro(item.getIntro()))
                    .append("\n");
            }
        }
        return builder.toString();
    }

    private int resolveTrendSnapshotRankLimit(List<RankSnapshotEntity> snapshots,
                                              Map<Long, List<CrawlRankEntity>> ranksBySnapshot) {
        if (snapshots == null || snapshots.isEmpty()) {
            return 30;
        }
        RankSnapshotEntity latestSnapshot = snapshots.get(0);
        int actualCount = ranksBySnapshot.getOrDefault(latestSnapshot.getId(), List.of()).size();
        if (actualCount > 0) {
            return actualCount;
        }
        Integer recordedCount = latestSnapshot.getRecordCount();
        if (recordedCount != null && recordedCount > 0) {
            return recordedCount;
        }
        return 30;
    }

    private List<CrawlRankEntity> selectTrendSnapshotRanks(RankSnapshotEntity snapshot,
                                                           Map<Long, List<CrawlRankEntity>> ranksBySnapshot,
                                                           int rankLimit) {
        int effectiveLimit = Math.max(1, rankLimit);
        return ranksBySnapshot.getOrDefault(snapshot.getId(), List.of()).stream()
            .sorted(Comparator.comparing(CrawlRankEntity::getRankNo, Comparator.nullsLast(Integer::compareTo)))
            .limit(effectiveLimit)
            .toList();
    }

    private String compactTrendIntro(String intro) {
        if (intro == null || intro.isBlank()) {
            return "";
        }
        String compact = intro.replaceAll("\\s+", " ").trim();
        if (compact.length() <= TREND_PROMPT_INTRO_MAX_LENGTH) {
            return compact;
        }
        return compact.substring(0, TREND_PROMPT_INTRO_MAX_LENGTH) + "...";
    }

    private Long findAnchorBookId(Map<Long, List<CrawlRankEntity>> ranksBySnapshot) {
        return ranksBySnapshot.values().stream()
            .flatMap(List::stream)
            .filter(item -> item.getBookId() != null)
            .min(Comparator.comparing(CrawlRankEntity::getRankNo, Comparator.nullsLast(Integer::compareTo)))
            .map(CrawlRankEntity::getBookId)
            .orElse(null);
    }

    private AiInvokeResult invokeAi(PromptConfigEntity promptConfig, String inputText, String analysisType) {
        return invokeAi(promptConfig, inputText, analysisType, null);
    }

    private AiInvokeResult invokeAi(PromptConfigEntity promptConfig,
                                    String inputText,
                                    String analysisType,
                                    Integer timeoutOverrideMillis) {
        long start = System.currentTimeMillis();
        AiInvokeResult aiResult = aiGatewayService.analyze(promptConfig, inputText, analysisType, timeoutOverrideMillis);
        long costTime = System.currentTimeMillis() - start;
        if (aiResult.getTokenUsed() <= 0) {
            aiResult.setTokenUsed(Math.max(120, inputText.length() / 2));
        }
        if (costTime > 0 && aiResult.getTokenUsed() > 0 && aiResult.getResultJson() == null) {
            aiResult.setResultJson(java.util.Map.of());
        }
        return aiResult;
    }

    private Long saveAnalysisResult(String platform,
                                    Long bookId,
                                    String analysisType,
                                    Integer chapterCount,
                                    Long promptConfigId,
                                    AiInvokeResult aiResult) {
        return saveAnalysisResult(platform, bookId, null, null, null, analysisType, chapterCount, promptConfigId, aiResult);
    }

    private Long saveAnalysisResult(String platform,
                                    Long bookId,
                                    String channelCode,
                                    String boardCode,
                                    Long snapshotId,
                                    String analysisType,
                                    Integer chapterCount,
                                    Long promptConfigId,
                                    AiInvokeResult aiResult) {
        AuthUser authUser = AuthUserHolder.get();
        Long userId = authUser == null ? null : authUser.getUserId();
        long costTime = resolveCostTime(aiResult);
        return analysisRepository.save(
            userId,
            platform,
            bookId,
            channelCode,
            boardCode,
            snapshotId,
            analysisType,
            chapterCount,
            promptConfigId,
            aiResult.getModelName(),
            aiResult.getContent(),
            writeResultJson(aiResult),
            aiResult.getTokenUsed(),
            costTime
        );
    }

    private TrendAnalysisVO buildTrendAnalysisVO(RankBoardEntity board,
                                                 AiInvokeResult aiResult,
                                                 int sourceSnapshotCount) {
        TrendAnalysisVO vo = new TrendAnalysisVO();
        vo.setAnalysisType("theme");
        vo.setPlatform(board.getPlatform());
        vo.setChannelCode(board.getChannelCode());
        vo.setBoardCode(board.getBoardCode());
        vo.setBoardName(board.getBoardName());
        vo.setModelName(aiResult.getModelName());
        vo.setResultJson(aiResult.getResultJson());
        vo.setResultContent(firstNonBlank(
            aiResult.getContent(),
            asString(aiResult.getResultJson().get("detailContent")),
            asString(aiResult.getResultJson().get("summary"))
        ));
        vo.setSourceSnapshotCount(sourceSnapshotCount);
        return vo;
    }

    private TrendAnalysisVO toTrendAnalysisVO(AnalysisResultEntity entity,
                                              RankBoardEntity board,
                                              int sourceSnapshotCount) {
        Map<String, Object> resultJson = normalizeTrendResultJson(
            readResultJson(entity.getResultJson(), entity.getAnalysisType()),
            board,
            sourceSnapshotCount,
            entity.getResultContent()
        );

        TrendAnalysisVO vo = new TrendAnalysisVO();
        vo.setAnalysisType("theme");
        vo.setPlatform(board.getPlatform());
        vo.setChannelCode(board.getChannelCode());
        vo.setBoardCode(board.getBoardCode());
        vo.setBoardName(board.getBoardName());
        vo.setModelName(entity.getModelName());
        vo.setResultContent(firstNonBlank(
            entity.getResultContent(),
            asString(resultJson.get("detailContent")),
            asString(resultJson.get("summary"))
        ));
        vo.setResultJson(resultJson);
        vo.setSourceSnapshotCount(sourceSnapshotCount);
        return vo;
    }

    private boolean isReusableStructuredTrendResult(AnalysisResultEntity entity,
                                                    RankBoardEntity board,
                                                    int snapshotCount) {
        if (entity == null) {
            return false;
        }
        Map<String, Object> normalized = normalizeTrendResultJson(
            readResultJson(entity.getResultJson(), entity.getAnalysisType()),
            board,
            snapshotCount,
            entity.getResultContent()
        );
        return TrendResultJsonUtils.hasReusableThemePayload(normalized);
    }

    private Map<String, Object> normalizeTrendResultJson(AiInvokeResult aiResult,
                                                         RankBoardEntity board,
                                                         int snapshotCount) {
        return normalizeTrendResultJson(
            aiResult == null ? null : aiResult.getResultJson(),
            board,
            snapshotCount,
            aiResult == null ? null : aiResult.getContent()
        );
    }

    private Map<String, Object> normalizeTrendResultJson(Map<String, Object> rawResult,
                                                         RankBoardEntity board,
                                                         int snapshotCount,
                                                         String fallbackContent) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawResult != null) {
            result.putAll(rawResult);
        }
        result = new LinkedHashMap<>(TrendResultJsonUtils.recoverThemeResultMap(objectMapper, result, fallbackContent));

        String summary = defaultString(TrendResultJsonUtils.extractThemeSummary(result));
        String boardSummary = defaultString(TrendResultJsonUtils.extractThemeBoardSummary(result));
        String detailContent = defaultString(TrendResultJsonUtils.extractThemeDetailContent(result, fallbackContent));
        List<Map<String, Object>> themeTable = normalizeThemeTable(result.get("themeTable"));
        List<Map<String, Object>> themeDistribution = normalizeThemeDistribution(
            result.get("themeDistribution"),
            result.get("themeTable")
        );
        List<Map<String, Object>> wordCloud = normalizeHistoricalWordCloud(
            coalesce(result.get("historicalWordCloud"), result.get("wordCloud"))
        );
        List<Map<String, Object>> hotBooks = normalizeHotBooks(result.get("hotBooks"));
        List<Map<String, Object>> insightCards = normalizeInsightCards(result.get("insightCards"));
        List<Map<String, Object>> snapshotComparisons = normalizeSnapshotComparisons(
            coalesce(result.get("snapshotComparisons"), result.get("snapshotComparison"))
        );

        result.put("analysisType", "theme");
        result.put("platform", board.getPlatform());
        result.put("channelCode", board.getChannelCode());
        result.put("boardCode", board.getBoardCode());
        result.put("boardName", board.getBoardName());
        result.put("boardSummary", boardSummary);
        result.put("historicalWordCloud", wordCloud);
        result.put("themeDistribution", themeDistribution);
        result.put("themeTable", themeTable);
        result.put("hotBooks", hotBooks);
        result.put("insightCards", insightCards);
        result.put("snapshotComparisons", snapshotComparisons);
        result.put("comparisonSummary", defaultString(asString(result.get("comparisonSummary"))));
        result.put("summary", summary);
        result.put("trendPreview", defaultString(TrendResultJsonUtils.extractThemeTrendPreview(result)));
        result.put("detailContent", detailContent);
        result.put("historyAnalysisCount", asInteger(result.get("historyAnalysisCount"), snapshotCount));
        return result;
    }

    private List<Map<String, Object>> normalizeHistoricalWordCloud(Object rawValue) {
        return TrendResultJsonUtils.normalizeThemeWordCloud(rawValue);
    }

    private List<Map<String, Object>> normalizeThemeDistribution(Object rawValue, Object themeTableFallback) {
        return TrendResultJsonUtils.normalizeThemeDistribution(rawValue, themeTableFallback);
    }

    private List<Map<String, Object>> normalizeThemeTable(Object rawValue) {
        return TrendResultJsonUtils.normalizeThemeTable(rawValue);
    }

    private List<Map<String, Object>> normalizeHotBooks(Object rawValue) {
        return TrendResultJsonUtils.normalizeThemeHotBooks(rawValue);
    }

    private List<Map<String, Object>> normalizeRepresentativeBooks(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(this::normalizeHotBook)
            .filter(Objects::nonNull)
            .toList();
    }

    private Map<String, Object> normalizeHotBook(Map<String, Object> item) {
        String bookName = asString(item.get("bookName"));
        if (bookName == null || bookName.isBlank()) {
            return null;
        }
        Integer rankNo = asIntegerOrNull(item.get("rankNo"));
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("theme", asString(item.get("theme")));
        normalized.put("bookName", bookName);
        normalized.put("author", asString(item.get("author")));
        normalized.put("rankNo", rankNo);
        normalized.put("rankLabel", normalizeRankLabel(rankNo, asString(item.get("rankLabel"))));
        normalized.put("reason", asString(item.get("reason")));
        return normalized;
    }

    private List<Map<String, Object>> normalizeInsightCards(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .filter(item -> asString(item.get("label")) != null && asString(item.get("value")) != null)
            .toList();
    }

    private List<Map<String, Object>> normalizeSnapshotComparisons(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("snapshotTime", asString(item.get("snapshotTime")));
                normalized.put("topTheme", asString(item.get("topTheme")));
                normalized.put("topThemeRatio", asDouble(item.get("topThemeRatio")));
                normalized.put("leadBookName", asString(item.get("leadBookName")));
                normalized.put("change", asString(item.get("change")));
                return normalized;
            })
            .filter(item -> asString(item.get("snapshotTime")) != null)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMap(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .toList();
    }

    private Object coalesce(Object first, Object second) {
        return first != null ? first : second;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int asInteger(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private Integer asIntegerOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeRankLabel(Integer rankNo, String rankLabel) {
        if (rankLabel != null && !rankLabel.isBlank()) {
            return rankLabel;
        }
        if (rankNo == null || rankNo <= 0) {
            return null;
        }
        return "#" + rankNo;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private long resolveCostTime(AiInvokeResult aiResult) {
        if (aiResult != null && aiResult.getResultJson() != null) {
            Object meta = aiResult.getResultJson().get("meta");
            if (meta instanceof Map<?, ?> metaMap) {
                Object runtime = metaMap.get("runtime");
                if (runtime instanceof Map<?, ?> runtimeMap) {
                    Object totalDurationMillis = runtimeMap.get("totalDurationMillis");
                    if (totalDurationMillis instanceof Number number && number.longValue() > 0L) {
                        return number.longValue();
                    }
                    try {
                        long parsed = Long.parseLong(String.valueOf(totalDurationMillis));
                        if (parsed > 0L) {
                            return parsed;
                        }
                    } catch (NumberFormatException ignored) {
                        // Fall through to the legacy approximation below.
                    }
                }
            }
        }
        return Math.max(1L, aiResult == null || aiResult.getContent() == null ? 1L : aiResult.getContent().length());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String shortText(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit).trim() + "...";
    }

    private AnalysisResultVO toAnalysisResultVO(AnalysisResultEntity entity) {
        AnalysisResultVO vo = new AnalysisResultVO();
        vo.setId(entity.getId());
        vo.setBookId(entity.getBookId());
        vo.setAnalysisType(entity.getAnalysisType());
        vo.setModelName(entity.getModelName());
        vo.setResultContent(entity.getResultContent());
        vo.setResultJson(readResultJson(entity.getResultJson(), entity.getAnalysisType()));
        vo.setTokenUsed(entity.getTokenUsed());
        return vo;
    }

    private Map<String, Object> readResultJson(String resultJson, String analysisType) {
        if (resultJson == null || resultJson.isBlank()) {
            return Map.of("analysisType", analysisType);
        }
        try {
            return objectMapper.readValue(resultJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of(
                "analysisType", analysisType,
                "raw", resultJson
            );
        }
    }

    private String writeResultJson(AiInvokeResult aiResult) {
        try {
            return objectMapper.writeValueAsString(aiResult.getResultJson());
        } catch (Exception ex) {
            return "{\"analysisType\":\"" + aiResult.getResultJson().getOrDefault("analysisType", "unknown") + "\"}";
        }
    }

    private void submitChunkAnalysis(ExecutorCompletionService<ChunkAnalysisOutcome> completionService,
                                     PromptConfigEntity promptConfig,
                                     CrawlBookEntity book,
                                     List<List<ChapterVO>> chunks,
                                     String analysisType,
                                     int timeoutMillis,
                                     java.util.function.Consumer<String> progressListener,
                                     AiGatewayService.InvocationHandle invocationHandle,
                                     int chunkIndex) {
        ensureNotCancelled(invocationHandle);
        List<ChapterVO> chunk = chunks.get(chunkIndex);
        emitChunkProgress(progressListener, buildChunkProgressMessage(chunkIndex + 1, chunks.size(), chunk, "正在分析"));
        completionService.submit(() -> analyzeSingleChunk(
            promptConfig,
            book,
            chunks.size(),
            analysisType,
            timeoutMillis,
            chunkIndex,
            chunk,
            invocationHandle
        ));
    }

    private ChunkAnalysisOutcome awaitChunkOutcome(ExecutorCompletionService<ChunkAnalysisOutcome> completionService,
                                                   AiGatewayService.InvocationHandle invocationHandle)
        throws InterruptedException, ExecutionException {
        while (true) {
            ensureNotCancelled(invocationHandle);
            Future<ChunkAnalysisOutcome> future = completionService.poll(200, TimeUnit.MILLISECONDS);
            if (future == null) {
                continue;
            }
            return future.get();
        }
    }

    private void ensureNotCancelled(AiGatewayService.InvocationHandle invocationHandle) {
        if (invocationHandle != null && invocationHandle.isCancelled()) {
            throw new AnalysisCancelledException();
        }
    }

    private record ChunkAnalysisOutcome(int index, List<ChapterVO> chunk, AiInvokeResult result) {
    }

    private static final class AnalysisCancelledException extends RuntimeException {
    }
}

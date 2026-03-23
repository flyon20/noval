package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.context.TraceIdHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.dto.AnalysisRequest;
import com.novelanalyzer.modules.analysis.dto.TrendAnalysisRequest;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.analysis.repository.AnalysisRepository;
import com.novelanalyzer.modules.analysis.vo.AnalysisResultVO;
import com.novelanalyzer.modules.analysis.vo.TrendAnalysisVO;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.service.CrawlerCacheService;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AnalysisService {

    private static final long ANALYSIS_TTL_SECONDS = 30L * 24 * 3600;
    private static final long STREAM_TIMEOUT_MILLIS = 0L;
    private static final int STREAM_CHUNK_SIZE = 120;
    private static final int DEFAULT_CHUNK_MAX_INPUT_TOKENS = 6000;
    private static final int DEFAULT_CHUNK_TARGET_INPUT_TOKENS = 3500;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptConfigRepository promptConfigRepository;
    private final CrawlerRepository crawlerRepository;
    private final AiGatewayService aiGatewayService;
    private final AnalysisRepository analysisRepository;
    private final CrawlerCacheService crawlerCacheService;
    private final CrawlerService crawlerService;
    private final com.novelanalyzer.modules.config.service.SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor streamTaskExecutor = new SimpleAsyncTaskExecutor("analysis-stream-");

    public AnalysisService(PromptConfigRepository promptConfigRepository,
                           CrawlerRepository crawlerRepository,
                           AiGatewayService aiGatewayService,
                           AnalysisRepository analysisRepository,
                           CrawlerCacheService crawlerCacheService,
                           CrawlerService crawlerService,
                           com.novelanalyzer.modules.config.service.SystemConfigService systemConfigService,
                           ObjectMapper objectMapper) {
        this.promptConfigRepository = promptConfigRepository;
        this.crawlerRepository = crawlerRepository;
        this.aiGatewayService = aiGatewayService;
        this.analysisRepository = analysisRepository;
        this.crawlerCacheService = crawlerCacheService;
        this.crawlerService = crawlerService;
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
    }

    public AnalysisResultVO analyze(String analysisType, AnalysisRequest request) {
        PromptConfigEntity promptConfig = promptConfigRepository.findDefaultByType(analysisType)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
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

        String inputText = buildBookInputText(book, chapters);
        AiInvokeResult aiResult = shouldUseChunkedAnalysis(promptConfig, analysisType, inputText, chapters)
            ? invokeChunkedAnalysis(promptConfig, book, chapters, analysisType)
            : invokeAi(promptConfig, inputText, analysisType);
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
        streamTaskExecutor.execute(() -> {
            try {
                restoreContext(authUser, traceId);
                sendStartEvent(emitter, traceId, analysisType);

                // 尝试真流式：非 chunk 场景直接推 token，避免等全文再切割
                PromptConfigEntity promptConfig = promptConfigRepository
                    .findDefaultByType(analysisType)
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
                List<ChapterVO> chapters = loadAnalysisChapters(request);
                if (chapters.isEmpty()) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "chapter content not found");
                }
                CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
                String inputText = buildBookInputText(book, chapters);
                boolean useChunk = shouldUseChunkedAnalysis(promptConfig, analysisType, inputText, chapters);

                if (!useChunk) {
                    boolean streamed = aiGatewayService.streamToEmitter(
                        promptConfig, inputText, analysisType, emitter,
                        (em, aiResult) -> {
                            try {
                                String cacheKey = buildAnalysisCacheKey(
                                    promptConfig, request.getBookId(), analysisType, request.getChapterCount());
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
                        analysisType
                    );
                    sendDoneEvent(emitter, result);
                    emitter.complete();
                    return;
                }

                // 降级：chunk 分析或流式不可用 → 阻塞等全文再切割推送
                AnalysisResultVO result = analyze(analysisType, request);
                sendDeltaEvents(emitter, result.getResultContent());
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

    public TrendAnalysisVO analyzeTrend(String platform, String category) {
        String normalizedCategory = category == null ? "" : category;
        PromptConfigEntity promptConfig = promptConfigRepository.findDefaultByType("theme")
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "theme prompt config not found"));
        String cacheKey = buildTrendCacheKey(promptConfig, platform, normalizedCategory);
        TrendAnalysisVO cached = crawlerCacheService.get(cacheKey, new TypeReference<TrendAnalysisVO>() {
        });
        if (cached != null) {
            return cached;
        }
        List<CrawlRankEntity> ranks = crawlerRepository.findRanks(platform, category);
        Map<LocalDateTime, List<CrawlRankEntity>> snapshots = takeLatestSnapshots(ranks, 3);
        if (snapshots.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "rank snapshot not found");
        }

        String inputText = buildTrendInputText(platform, category, snapshots);
        AiInvokeResult aiResult = invokeAi(promptConfig, inputText, "theme");
        Long anchorBookId = snapshots.values().iterator().next().get(0).getBookId();
        saveAnalysisResult(platform, anchorBookId, "theme", 0, promptConfig.getId(), aiResult);

        TrendAnalysisVO vo = new TrendAnalysisVO();
        vo.setAnalysisType("theme");
        vo.setPlatform(platform);
        vo.setCategory(category);
        vo.setModelName(aiResult.getModelName());
        vo.setResultContent(aiResult.getContent());
        vo.setResultJson(aiResult.getResultJson());
        vo.setSourceSnapshotCount(snapshots.size());
        crawlerCacheService.put(cacheKey, vo, ANALYSIS_TTL_SECONDS);
        return vo;
    }

    public SseEmitter streamTrend(TrendAnalysisRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AuthUser authUser = copyAuthUser(AuthUserHolder.get());
        String traceId = TraceIdHolder.get();
        String normalizedCategory = request.getCategory() == null || request.getCategory().isBlank()
            ? null
            : request.getCategory();
        streamTaskExecutor.execute(() -> {
            try {
                restoreContext(authUser, traceId);
                sendStartEvent(emitter, traceId, "theme");
                TrendAnalysisVO result = analyzeTrend(request.getPlatform(), normalizedCategory);
                sendDeltaEvents(emitter, result.getResultContent());
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

    private String buildAnalysisCacheKey(PromptConfigEntity promptConfig,
                                         Long bookId,
                                         String analysisType,
                                         Integer chapterCount) {
        return "analysis:" + bookId + ":" + analysisType + ":" + chapterCount + ":" + buildPromptSignature(promptConfig);
    }

    private String buildTrendCacheKey(PromptConfigEntity promptConfig, String platform, String category) {
        return "analysis:trend:" + platform + ":" + category + ":" + buildPromptSignature(promptConfig);
    }

    private boolean shouldUseChunkedAnalysis(PromptConfigEntity promptConfig,
                                             String analysisType,
                                             String inputText,
                                             List<ChapterVO> chapters) {
        if (!supportsChunkedAnalysis(analysisType) || chapters == null || chapters.size() <= 1) {
            return false;
        }
        return aiGatewayService.estimatePromptTokens(promptConfig, inputText, analysisType) > resolveChunkMaxInputTokens();
    }

    private boolean supportsChunkedAnalysis(String analysisType) {
        return "deconstruct".equals(analysisType)
            || "structure".equals(analysisType)
            || "plot".equals(analysisType);
    }

    private int resolveChunkMaxInputTokens() {
        return Math.max(
            1000,
            systemConfigService.getIntValueOrDefault("analysis.chunk.max-input-tokens", DEFAULT_CHUNK_MAX_INPUT_TOKENS)
        );
    }

    private int resolveChunkTargetInputTokens() {
        return Math.max(
            1000,
            systemConfigService.getIntValueOrDefault("analysis.chunk.target-input-tokens", DEFAULT_CHUNK_TARGET_INPUT_TOKENS)
        );
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

    private AiInvokeResult invokeChunkedAnalysis(PromptConfigEntity promptConfig,
                                                 CrawlBookEntity book,
                                                 List<ChapterVO> chapters,
                                                 String analysisType) {
        return invokeChunkedAnalysis(promptConfig, book, chapters, analysisType, null);
    }

    private AiInvokeResult invokeChunkedAnalysis(PromptConfigEntity promptConfig,
                                                 CrawlBookEntity book,
                                                 List<ChapterVO> chapters,
                                                 String analysisType,
                                                 java.util.function.Consumer<String> progressListener) {
        List<List<ChapterVO>> chunks = splitChaptersForChunkedAnalysis(promptConfig, book, chapters, analysisType);
        if (chunks.size() <= 1) {
            return invokeAi(promptConfig, buildBookInputText(book, chapters), analysisType);
        }

        List<String> chunkOutputs = new ArrayList<>();
        int tokenUsed = 0;
        for (int index = 0; index < chunks.size(); index++) {
            List<ChapterVO> chunk = chunks.get(index);
            emitChunkProgress(progressListener, buildChunkProgressMessage(index + 1, chunks.size(), chunk, "正在分析"));
            String chunkInput = buildBookInputText(book, chunk);
            PromptConfigEntity chunkPrompt = copyPromptConfig(
                promptConfig,
                buildChunkPromptTemplate(promptConfig, analysisType, index + 1, chunks.size())
            );
            AiInvokeResult chunkResult = aiGatewayService.analyze(chunkPrompt, chunkInput, analysisType);
            tokenUsed += Math.max(0, chunkResult.getTokenUsed());
            chunkOutputs.add(buildChunkResultText(index + 1, chunk, chunkResult));
            emitChunkProgress(progressListener, buildChunkProgressMessage(index + 1, chunks.size(), chunk, "已完成"));
        }

        emitChunkProgress(progressListener, "\n[chunk-progress] 分段分析已完成，正在汇总最终结果...\n");

        PromptConfigEntity mergePrompt = copyPromptConfig(
            promptConfig,
            buildChunkMergePromptTemplate(promptConfig, analysisType)
        );
        AiInvokeResult mergedResult = aiGatewayService.analyze(
            mergePrompt,
            buildChunkMergeInput(book, chunkOutputs),
            analysisType
        );
        tokenUsed += Math.max(0, mergedResult.getTokenUsed());

        Map<String, Object> resultJson = new HashMap<>(mergedResult.getResultJson() == null ? Map.of() : mergedResult.getResultJson());
        resultJson.put("analysisMode", "chunk_merge");
        resultJson.put("segmentCount", chunks.size());
        resultJson.put("inputChapterCount", chapters.size());
        mergedResult.setResultJson(resultJson);
        mergedResult.setTokenUsed(tokenUsed);
        return mergedResult;
    }

    private List<List<ChapterVO>> splitChaptersForChunkedAnalysis(PromptConfigEntity promptConfig,
                                                                  CrawlBookEntity book,
                                                                  List<ChapterVO> chapters,
                                                                  String analysisType) {
        List<List<ChapterVO>> result = new ArrayList<>();
        List<ChapterVO> current = new ArrayList<>();
        int targetTokens = resolveChunkTargetInputTokens();
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
                                                   String analysisType) throws IOException {
        AiInvokeResult aiResult = invokeChunkedAnalysis(
            promptConfig,
            book,
            chapters,
            analysisType,
            progress -> {
                try {
                    sendDeltaEvent(emitter, progress, null);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        );

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

    private Map<LocalDateTime, List<CrawlRankEntity>> takeLatestSnapshots(List<CrawlRankEntity> ranks, int snapshotCount) {
        Map<LocalDateTime, List<CrawlRankEntity>> snapshots = new LinkedHashMap<>();
        for (CrawlRankEntity rank : ranks) {
            if (rank.getCrawlTime() == null) {
                continue;
            }
            if (!snapshots.containsKey(rank.getCrawlTime()) && snapshots.size() >= snapshotCount) {
                continue;
            }
            snapshots.computeIfAbsent(rank.getCrawlTime(), key -> new ArrayList<>()).add(rank);
        }
        return snapshots;
    }

    private String buildTrendInputText(String platform, String category, Map<LocalDateTime, List<CrawlRankEntity>> snapshots) {
        StringBuilder builder = new StringBuilder();
        builder.append("Platform: ").append(platform).append("\n");
        if (category != null && !category.isBlank()) {
            builder.append("Category: ").append(category).append("\n");
        }
        int index = 1;
        for (Map.Entry<LocalDateTime, List<CrawlRankEntity>> entry : snapshots.entrySet()) {
            builder.append("Snapshot ").append(index++).append(" @ ")
                .append(entry.getKey().format(DATE_TIME_FORMATTER)).append("\n");
            for (CrawlRankEntity item : entry.getValue()) {
                builder.append("- #").append(item.getRankNo())
                    .append(" ").append(item.getBookName())
                    .append(" / ").append(item.getAuthor())
                    .append(" / ").append(item.getIntro())
                    .append("\n");
            }
        }
        return builder.toString();
    }

    private AiInvokeResult invokeAi(PromptConfigEntity promptConfig, String inputText, String analysisType) {
        long start = System.currentTimeMillis();
        AiInvokeResult aiResult = aiGatewayService.analyze(promptConfig, inputText, analysisType);
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
        AuthUser authUser = AuthUserHolder.get();
        Long userId = authUser == null ? null : authUser.getUserId();
        long costTime = Math.max(1L, aiResult.getContent() == null ? 1L : aiResult.getContent().length());
        return analysisRepository.save(
            userId,
            platform,
            bookId,
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
}

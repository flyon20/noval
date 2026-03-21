package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.dto.AnalysisRequest;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.analysis.repository.AnalysisRepository;
import com.novelanalyzer.modules.analysis.vo.AnalysisResultVO;
import com.novelanalyzer.modules.analysis.vo.TrendAnalysisVO;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.service.CrawlerCacheService;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AnalysisService {

    private static final long ANALYSIS_TTL_SECONDS = 30L * 24 * 3600;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptConfigRepository promptConfigRepository;
    private final CrawlerRepository crawlerRepository;
    private final AiGatewayService aiGatewayService;
    private final AnalysisRepository analysisRepository;
    private final CrawlerCacheService crawlerCacheService;
    private final ObjectMapper objectMapper;

    public AnalysisService(PromptConfigRepository promptConfigRepository,
                           CrawlerRepository crawlerRepository,
                           AiGatewayService aiGatewayService,
                           AnalysisRepository analysisRepository,
                           CrawlerCacheService crawlerCacheService,
                           ObjectMapper objectMapper) {
        this.promptConfigRepository = promptConfigRepository;
        this.crawlerRepository = crawlerRepository;
        this.aiGatewayService = aiGatewayService;
        this.analysisRepository = analysisRepository;
        this.crawlerCacheService = crawlerCacheService;
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
        CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        List<ChapterVO> chapters = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
        if (chapters.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter content not found");
        }

        String inputText = buildBookInputText(book, chapters);
        AiInvokeResult aiResult = invokeAi(promptConfig, inputText, analysisType);
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

    private String buildAnalysisCacheKey(PromptConfigEntity promptConfig,
                                         Long bookId,
                                         String analysisType,
                                         Integer chapterCount) {
        return "analysis:" + bookId + ":" + analysisType + ":" + chapterCount + ":" + buildPromptSignature(promptConfig);
    }

    private String buildTrendCacheKey(PromptConfigEntity promptConfig, String platform, String category) {
        return "analysis:trend:" + platform + ":" + category + ":" + buildPromptSignature(promptConfig);
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

    private String writeResultJson(AiInvokeResult aiResult) {
        try {
            return objectMapper.writeValueAsString(aiResult.getResultJson());
        } catch (Exception ex) {
            return "{\"analysisType\":\"" + aiResult.getResultJson().getOrDefault("analysisType", "unknown") + "\"}";
        }
    }
}

package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.dto.AnalysisRequest;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.analysis.repository.AnalysisRepository;
import com.novelanalyzer.modules.analysis.vo.AnalysisResultVO;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.service.CrawlerCacheService;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisService {

    private static final long ANALYSIS_TTL_SECONDS = 30L * 24 * 3600;

    private final PromptConfigRepository promptConfigRepository;
    private final CrawlerRepository crawlerRepository;
    private final AiGatewayService aiGatewayService;
    private final AnalysisRepository analysisRepository;
    private final CrawlerCacheService crawlerCacheService;

    public AnalysisService(PromptConfigRepository promptConfigRepository,
                           CrawlerRepository crawlerRepository,
                           AiGatewayService aiGatewayService,
                           AnalysisRepository analysisRepository,
                           CrawlerCacheService crawlerCacheService) {
        this.promptConfigRepository = promptConfigRepository;
        this.crawlerRepository = crawlerRepository;
        this.aiGatewayService = aiGatewayService;
        this.analysisRepository = analysisRepository;
        this.crawlerCacheService = crawlerCacheService;
    }

    public AnalysisResultVO analyze(String analysisType, AnalysisRequest request) {
        String cacheKey = "analysis:" + request.getBookId() + ":" + analysisType + ":" + request.getChapterCount();
        AnalysisResultVO cached = crawlerCacheService.get(cacheKey, new TypeReference<AnalysisResultVO>() {
        });
        if (cached != null) {
            return cached;
        }

        PromptConfigEntity promptConfig = promptConfigRepository.findDefaultByType(analysisType)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
        CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        List<ChapterVO> chapters = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
        if (chapters.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter content not found");
        }

        String inputText = buildInputText(book, chapters);
        long start = System.currentTimeMillis();
        AiInvokeResult aiResult = aiGatewayService.analyze(promptConfig, inputText, analysisType);
        long costTime = System.currentTimeMillis() - start;

        AuthUser authUser = AuthUserHolder.get();
        Long userId = authUser == null ? null : authUser.getUserId();
        Long resultId = analysisRepository.save(
            userId,
            request.getPlatform(),
            request.getBookId(),
            analysisType,
            request.getChapterCount(),
            promptConfig.getId(),
            aiResult.getModelName(),
            aiResult.getContent(),
            aiResult.getTokenUsed(),
            costTime
        );

        AnalysisResultVO vo = new AnalysisResultVO();
        vo.setId(resultId);
        vo.setBookId(request.getBookId());
        vo.setAnalysisType(analysisType);
        vo.setModelName(aiResult.getModelName());
        vo.setResultContent(aiResult.getContent());
        vo.setTokenUsed(aiResult.getTokenUsed());
        crawlerCacheService.put(cacheKey, vo, ANALYSIS_TTL_SECONDS);
        return vo;
    }

    private String buildInputText(CrawlBookEntity book, List<ChapterVO> chapters) {
        StringBuilder builder = new StringBuilder();
        builder.append("书名: ").append(book.getBookName()).append("\n");
        builder.append("作者: ").append(book.getAuthor()).append("\n");
        builder.append("简介: ").append(book.getIntro()).append("\n");
        for (ChapterVO chapter : chapters) {
            builder.append("[").append(chapter.getChapterTitle()).append("] ")
                .append(chapter.getContent()).append("\n");
        }
        return builder.toString();
    }
}


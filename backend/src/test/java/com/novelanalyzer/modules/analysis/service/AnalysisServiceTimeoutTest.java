package com.novelanalyzer.modules.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.analysis.model.AiInvokeResult;
import com.novelanalyzer.modules.analysis.repository.AnalysisRepository;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.service.UserConfigService;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.service.CrawlerCacheService;
import com.novelanalyzer.modules.crawler.service.CrawlerService;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisServiceTimeoutTest {

    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final AnalysisService analysisService = new AnalysisService(
        mock(PromptConfigRepository.class),
        mock(CrawlerRepository.class),
        mock(AiGatewayService.class),
        mock(AnalysisRepository.class),
        mock(CrawlerCacheService.class),
        mock(CrawlerService.class),
        mock(LangGraphWorkerClient.class),
        systemConfigService,
        mock(UserConfigService.class),
        new ObjectMapper(),
        mock(AsyncTaskExecutor.class)
    );

    @Test
    void shouldUseLongerTimeoutBudgetForTrendAnalysisRequests() {
        when(systemConfigService.getIntValueOrDefault("ai.timeout.millis", 15000)).thenReturn(15000);

        Integer timeoutMillis = ReflectionTestUtils.invokeMethod(
            analysisService,
            "resolveLangGraphTimeoutMillis",
            "theme"
        );

        assertThat(timeoutMillis).isEqualTo(180000);
    }

    @Test
    void shouldKeepRegularTimeoutBudgetForSingleBookAnalysisRequests() {
        when(systemConfigService.getIntValueOrDefault("ai.timeout.millis", 15000)).thenReturn(15000);

        Integer timeoutMillis = ReflectionTestUtils.invokeMethod(
            analysisService,
            "resolveLangGraphTimeoutMillis",
            "deconstruct"
        );

        assertThat(timeoutMillis).isEqualTo(15000);
    }

    @Test
    void shouldPreferHigherRuntimeModelTokenBudgetForTrendAnalysis() {
        PromptConfigEntity promptConfig = new PromptConfigEntity();
        promptConfig.setMaxTokens(6000);
        AiModelRegistryModelVO runtimeModel = new AiModelRegistryModelVO();
        runtimeModel.setMaxTokens(8192);

        Integer maxTokens = ReflectionTestUtils.invokeMethod(
            analysisService,
            "resolveLangGraphMaxTokens",
            promptConfig,
            runtimeModel,
            "theme"
        );

        assertThat(maxTokens).isEqualTo(8192);
    }

    @Test
    void shouldKeepPromptTokenBudgetForSingleBookAnalysisWhenAlreadyConfigured() {
        PromptConfigEntity promptConfig = new PromptConfigEntity();
        promptConfig.setMaxTokens(6000);
        AiModelRegistryModelVO runtimeModel = new AiModelRegistryModelVO();
        runtimeModel.setMaxTokens(8192);

        Integer maxTokens = ReflectionTestUtils.invokeMethod(
            analysisService,
            "resolveLangGraphMaxTokens",
            promptConfig,
            runtimeModel,
            "deconstruct"
        );

        assertThat(maxTokens).isEqualTo(6000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLimitTrendPayloadToLatestSnapshotRecordCount() {
        when(systemConfigService.resolveEnabledModel(null, "deepseek-chat")).thenReturn(Optional.empty());

        PromptConfigEntity promptConfig = new PromptConfigEntity();
        promptConfig.setPromptType("theme");
        promptConfig.setModelName("deepseek-chat");

        RankBoardEntity board = new RankBoardEntity();
        board.setPlatform("fanqie");
        board.setChannelCode("male-new");
        board.setBoardCode("257");
        board.setBoardName("玄幻脑洞");

        RankSnapshotEntity latestSnapshot = createSnapshot(97L, 20, LocalDateTime.of(2026, 3, 25, 16, 59, 45));
        RankSnapshotEntity middleSnapshot = createSnapshot(96L, 20, LocalDateTime.of(2026, 3, 25, 16, 59, 40));
        RankSnapshotEntity legacySnapshot = createSnapshot(84L, 100, LocalDateTime.of(2026, 3, 25, 0, 23, 26));
        List<RankSnapshotEntity> snapshots = List.of(latestSnapshot, middleSnapshot, legacySnapshot);

        Map<Long, List<CrawlRankEntity>> ranksBySnapshot = new LinkedHashMap<>();
        ranksBySnapshot.put(97L, createRanks(97L, 20));
        ranksBySnapshot.put(96L, createRanks(96L, 20));
        ranksBySnapshot.put(84L, createRanks(84L, 100));

        Map<String, Object> request = ReflectionTestUtils.invokeMethod(
            analysisService,
            "buildLangGraphTrendRequest",
            promptConfig,
            board,
            snapshots,
            ranksBySnapshot,
            false
        );

        Map<String, Object> sourcePayload = (Map<String, Object>) request.get("sourcePayload");
        List<Map<String, Object>> snapshotPayload = (List<Map<String, Object>>) sourcePayload.get("snapshots");
        String inputText = (String) sourcePayload.get("inputText");

        assertThat(snapshotPayload).hasSize(3);
        assertThat((Integer) snapshotPayload.get(0).get("recordCount")).isEqualTo(20);
        assertThat((Integer) snapshotPayload.get(1).get("recordCount")).isEqualTo(20);
        assertThat((Integer) snapshotPayload.get(2).get("recordCount")).isEqualTo(20);
        assertThat((List<Map<String, Object>>) snapshotPayload.get(2).get("ranks")).hasSize(20);
        assertThat(inputText).contains("#20 Book 20");
        assertThat(inputText).doesNotContain("#21 Book 21");
    }

    @Test
    void shouldTruncateLongTrendIntroSnippetsInPromptInput() {
        RankBoardEntity board = new RankBoardEntity();
        board.setPlatform("fanqie");
        board.setChannelCode("male-new");
        board.setBoardCode("257");
        board.setBoardName("玄幻脑洞");

        RankSnapshotEntity snapshot = createSnapshot(97L, 1, LocalDateTime.of(2026, 3, 25, 16, 59, 45));
        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setSnapshotId(97L);
        rank.setRankNo(1);
        rank.setBookId(1L);
        rank.setBookName("Book 1");
        rank.setAuthor("Author 1");
        rank.setIntro("Lead-" + "A".repeat(220) + "-Tail");

        String inputText = ReflectionTestUtils.invokeMethod(
            analysisService,
            "buildTrendInputText",
            board,
            List.of(snapshot),
            Map.of(97L, List.of(rank))
        );

        assertThat(inputText).contains("Lead-");
        assertThat(inputText).contains("...");
        assertThat(inputText).doesNotContain("-Tail");
    }

    @Test
    void shouldUseLongerTimeoutBudgetForTenChapterBookAnalysis() {
        when(systemConfigService.getIntValueOrDefault("ai.timeout.millis", 15000)).thenReturn(15000);

        Integer timeoutMillis = ReflectionTestUtils.invokeMethod(
            analysisService,
            "resolveBookAnalysisTimeoutMillis",
            10,
            8,
            true
        );

        assertThat(timeoutMillis).isEqualTo(60000);
    }

    @Test
    void shouldKeepDefaultTimeoutBudgetForShortBookAnalysis() {
        when(systemConfigService.getIntValueOrDefault("ai.timeout.millis", 15000)).thenReturn(15000);

        Integer timeoutMillis = ReflectionTestUtils.invokeMethod(
            analysisService,
            "resolveBookAnalysisTimeoutMillis",
            3,
            3,
            false
        );

        assertThat(timeoutMillis).isEqualTo(15000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldForceChunkSplitForLargeBookAnalysisEvenWhenChapterTextIsShort() {
        PromptConfigEntity promptConfig = new PromptConfigEntity();
        promptConfig.setModelName("deepseek-chat");

        com.novelanalyzer.modules.crawler.model.CrawlBookEntity book = new com.novelanalyzer.modules.crawler.model.CrawlBookEntity();
        book.setBookName("Book A");
        book.setAuthor("Author A");
        book.setIntro("Intro A");

        List<ChapterVO> chapters = java.util.stream.IntStream.rangeClosed(1, 8)
            .mapToObj(index -> {
                ChapterVO chapter = new ChapterVO();
                chapter.setChapterNo(index);
                chapter.setChapterTitle("Chapter " + index);
                chapter.setContent("short content " + index);
                return chapter;
            })
            .toList();

        List<List<ChapterVO>> chunks = ReflectionTestUtils.invokeMethod(
            analysisService,
            "splitChaptersForChunkedAnalysis",
            promptConfig,
            book,
            chapters,
            "deconstruct"
        );

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0)).hasSizeLessThan(chapters.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAttachActualAndRequestedChapterCountsToBookAnalysisResult() {
        AiInvokeResult aiInvokeResult = AiInvokeResult.of(
            "deepseek-chat",
            "analysis result",
            128,
            new LinkedHashMap<>(Map.of("summary", "summary"))
        );

        ReflectionTestUtils.invokeMethod(
            analysisService,
            "attachBookAnalysisMeta",
            aiInvokeResult,
            10,
            8
        );

        Map<String, Object> resultJson = aiInvokeResult.getResultJson();
        assertThat(resultJson.get("requestedChapterCount")).isEqualTo(10);
        assertThat(resultJson.get("actualChapterCount")).isEqualTo(8);
        assertThat(resultJson.get("inputChapterCount")).isEqualTo(8);
        assertThat(resultJson.get("chapterFetchDegraded")).isEqualTo(true);
    }

    private RankSnapshotEntity createSnapshot(Long id, int recordCount, LocalDateTime snapshotTime) {
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(id);
        snapshot.setRecordCount(recordCount);
        snapshot.setSnapshotTime(snapshotTime);
        return snapshot;
    }

    private List<CrawlRankEntity> createRanks(Long snapshotId, int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(index -> {
                CrawlRankEntity entity = new CrawlRankEntity();
                entity.setSnapshotId(snapshotId);
                entity.setRankNo(index);
                entity.setBookId((long) index);
                entity.setBookName("Book " + index);
                entity.setAuthor("Author " + index);
                entity.setIntro("Intro " + index);
                return entity;
            })
            .toList();
    }
}

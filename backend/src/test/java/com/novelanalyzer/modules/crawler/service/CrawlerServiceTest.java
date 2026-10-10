package com.novelanalyzer.modules.crawler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobLockService;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankRefreshIdempotencyEntry;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import com.novelanalyzer.modules.crawler.vo.RankPageVO;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrawlerServiceTest {

    @Mock
    private PythonCrawlerClient pythonCrawlerClient;

    @Mock
    private CrawlerRepository crawlerRepository;

    @Mock
    private CrawlerRankPersistenceService crawlerRankPersistenceService;

    @Mock
    private CrawlerCacheService crawlerCacheService;

    @Mock
    private CrawlerRefreshPolicyService crawlerRefreshPolicyService;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private AsyncJobLockService asyncJobLockService;

    @Mock
    private CrawlerChapterPersistenceService crawlerChapterPersistenceService;

    @Mock
    private CrawlerFetchGuard crawlerFetchGuard;

    @InjectMocks
    private CrawlerService crawlerService;

    @BeforeEach
    void preserveCommittedResultWhenPersistenceServiceIsMocked() {
        lenient().doAnswer(invocation -> invocation.getArgument(1))
            .when(crawlerRankPersistenceService)
            .commitReusedResult(
                nullable(CrawlerRankPersistenceService.IdempotencyContext.class),
                any(RankRefreshResultVO.class)
            );
        lenient().when(crawlerCacheService.putIfAbsent(
            anyString(),
            any(RankRefreshIdempotencyEntry.class),
            anyLong()
        )).thenReturn(true);
        lenient().when(crawlerCacheService.compareAndSet(
            anyString(),
            any(RankRefreshIdempotencyEntry.class),
            any(RankRefreshIdempotencyEntry.class),
            anyLong()
        )).thenReturn(true);
        lenient().when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        lenient().when(asyncJobLockService.renewStrict(anyString(), anyString(), anyLong())).thenReturn(true);
    }

    @AfterEach
    void clearAuthContext() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldReadChapterStatusWithoutStartingCrawlerFetch() {
        CrawlerChapterRequest request = new CrawlerChapterRequest();
        request.setPlatform("fanqie");
        request.setBookId(42L);
        request.setChapterCount(3);
        List<ChapterVO> chapters = List.of(completeChapter(1));
        when(crawlerRepository.findBookById(42L)).thenReturn(Optional.of(crawlBook(42L)));
        when(crawlerRepository.findChapters(42L, 3)).thenReturn(chapters);

        assertThat(crawlerService.getChapterStatus(request)).isSameAs(chapters);

        verifyNoInteractions(pythonCrawlerClient);
    }

    @Test
    void shouldNotFetchChaptersWithoutOwningTheCrawlerLock() {
        CrawlBookEntity book = new CrawlBookEntity();
        book.setId(42L);
        book.setPlatform("fanqie");
        book.setBookUrl("https://fanqienovel.com/page/42");
        CrawlerChapterRequest request = chapterRequest(42L, 3);
        when(crawlerRepository.findBookById(42L)).thenReturn(Optional.of(book));
        when(crawlerRepository.findChapters(42L, 3)).thenReturn(List.of());
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> crawlerService.getChapters(request))
            .isInstanceOf(CrawlerService.ChapterFetchInProgressException.class)
            .satisfies(error -> assertThat(((CrawlerService.ChapterFetchInProgressException) error).getResultCode())
                .isEqualTo(ResultCode.CONFLICT))
            .hasMessage(CrawlerService.CHAPTER_FETCH_IN_PROGRESS);

        verify(pythonCrawlerClient, never()).fetchChapters(
            anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()
        );
        verify(asyncJobLockService).tryAcquireStrict(
            eq("lock:chapter-fetch:fanqie:42"),
            anyString(),
            anyLong()
        );
    }

    @Test
    void shouldNotForceRefreshChaptersWithoutOwningTheCrawlerLock() {
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        CrawlerChapterRequest request = chapterRequest(42L, 3);
        when(crawlerRefreshPolicyService.chapterForceRefreshUserMaxTimes()).thenReturn(3);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> crawlerService.refreshChapters(request))
            .isInstanceOf(CrawlerService.ChapterFetchInProgressException.class)
            .hasMessage(CrawlerService.CHAPTER_FETCH_IN_PROGRESS);

        verify(pythonCrawlerClient, never()).fetchChapters(
            anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()
        );
        verify(crawlerRepository, never()).saveChapterRefreshTask(
            anyLong(), anyString(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any()
        );
    }

    @Test
    void shouldRenewChapterLockWithExactOwnerToken() {
        AtomicBoolean healthy = new AtomicBoolean(true);
        when(asyncJobLockService.renewStrict("lock:chapter-fetch:fanqie:42", "owner-token", 120L))
            .thenReturn(true);

        boolean renewed = crawlerService.renewChapterLock(
            "lock:chapter-fetch:fanqie:42",
            "owner-token",
            healthy
        );

        assertThat(renewed).isTrue();
        assertThat(healthy).isTrue();
        verify(asyncJobLockService).renewStrict(
            "lock:chapter-fetch:fanqie:42",
            "owner-token",
            120L
        );
    }

    @Test
    void shouldStopBeforeChapterWritesWhenOwnershipIsLostAfterFetch() {
        CrawlerService service = serviceWithRealChapterPersistence();
        CrawlBookEntity book = crawlBook(42L);
        CrawlerChapterRequest request = chapterRequest(42L, 1);
        when(crawlerRepository.findBookById(42L)).thenReturn(Optional.of(book));
        when(crawlerRepository.findChapters(42L, 1)).thenReturn(List.of());
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        when(asyncJobLockService.renewStrict(anyString(), anyString(), anyLong()))
            .thenReturn(true, false);
        when(pythonCrawlerClient.fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(List.of(externalChapter(1)));

        assertThatThrownBy(() -> service.getChapters(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("chapter fetch lock ownership lost");

        verify(pythonCrawlerClient).fetchChapters(
            anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()
        );
        verify(crawlerRepository, never()).saveOrUpdateChapter(
            anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()
        );
        verify(crawlerCacheService, never()).put(anyString(), any(), anyLong());
    }

    @Test
    void shouldNotWriteForcedRefreshTaskAfterChapterLockIsLost() {
        CrawlerService service = serviceWithRealChapterPersistence();
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        CrawlBookEntity book = crawlBook(42L);
        CrawlerChapterRequest request = chapterRequest(42L, 1);
        when(crawlerRefreshPolicyService.chapterForceRefreshUserMaxTimes()).thenReturn(3);
        when(crawlerRepository.findBookById(42L)).thenReturn(Optional.of(book));
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        when(asyncJobLockService.renewStrict(anyString(), anyString(), anyLong()))
            .thenReturn(true, false);
        when(pythonCrawlerClient.fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(List.of(externalChapter(1)));

        assertThatThrownBy(() -> service.refreshChapters(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("chapter fetch lock ownership lost");

        verify(crawlerRepository, never()).saveOrUpdateChapter(
            anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()
        );
        verify(crawlerRepository, never()).saveChapterRefreshTask(
            anyLong(), anyString(), anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any()
        );
    }

    @Test
    void shouldSerializeConcurrentChapterCountsWithOneBookLevelLock() throws Exception {
        CrawlerService service = serviceWithRealChapterPersistence();
        CrawlBookEntity book = crawlBook(42L);
        AtomicInteger persistedCount = new AtomicInteger();
        AtomicInteger lockAttempts = new AtomicInteger();
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowFetchToFinish = new CountDownLatch(1);
        List<ChapterVO> completed = List.of(
            completeChapter(1),
            completeChapter(2),
            completeChapter(3)
        );
        when(crawlerRepository.findBookById(42L)).thenReturn(Optional.of(book));
        when(crawlerRepository.findChapters(eq(42L), anyInt())).thenAnswer(invocation ->
            persistedCount.get() >= 3 ? completed : List.of()
        );
        when(asyncJobLockService.tryAcquireStrict(
            eq("lock:chapter-fetch:fanqie:42"),
            anyString(),
            anyLong()
        )).thenAnswer(invocation -> lockAttempts.incrementAndGet() == 1);
        when(asyncJobLockService.renewStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        when(pythonCrawlerClient.fetchChapters(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenAnswer(invocation -> {
                fetchStarted.countDown();
                assertThat(allowFetchToFinish.await(5, TimeUnit.SECONDS)).isTrue();
                return List.of(externalChapter(1), externalChapter(2), externalChapter(3));
            });
        doAnswer(invocation -> {
            persistedCount.incrementAndGet();
            return null;
        }).when(crawlerRepository).saveOrUpdateChapter(
            anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()
        );

        CompletableFuture<List<ChapterVO>> owner = CompletableFuture.supplyAsync(
            () -> service.getChapters(chapterRequest(42L, 3))
        );
        assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> service.getChapters(chapterRequest(42L, 10)))
                .isInstanceOf(CrawlerService.ChapterFetchInProgressException.class)
                .hasMessage(CrawlerService.CHAPTER_FETCH_IN_PROGRESS);
        } finally {
            allowFetchToFinish.countDown();
        }

        assertThat(owner.get(5, TimeUnit.SECONDS)).hasSize(3);
        assertThat(lockAttempts).hasValue(2);
        verify(pythonCrawlerClient, times(1)).fetchChapters(
            anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()
        );
        verify(asyncJobLockService, times(2)).tryAcquireStrict(
            eq("lock:chapter-fetch:fanqie:42"),
            anyString(),
            anyLong()
        );
    }

    @Test
    void shouldReuseCompletedChaptersFoundAfterLockAcquisition() {
        CrawlerService service = serviceWithRealChapterPersistence();
        CrawlBookEntity book = crawlBook(42L);
        List<ChapterVO> completed = List.of(completeChapter(1), completeChapter(2), completeChapter(3));
        when(crawlerRepository.findBookById(42L)).thenReturn(Optional.of(book));
        when(crawlerRepository.findChapters(42L, 3)).thenReturn(List.of(), completed);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);

        List<ChapterVO> result = service.getChapters(chapterRequest(42L, 3));

        assertThat(result).isSameAs(completed);
        verify(pythonCrawlerClient, never()).fetchChapters(
            anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()
        );
        verify(asyncJobLockService).release(eq("lock:chapter-fetch:fanqie:42"), anyString());
    }

    @Test
    void shouldClampRankPageSizeToMaxWhenRequestIsTooLarge() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(10L);
        snapshot.setSnapshotTime(LocalDateTime.of(2026, 5, 10, 14, 0));
        snapshot.setRecordCount(1);

        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Book 01");
        rank.setAuthor("Author 01");
        rank.setIntro("Intro 01");
        rank.setBookUrl("https://fanqienovel.com/page/1");
        rank.setPlatform("fanqie");
        rank.setCategory("urban-brain");

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(snapshot));
        when(crawlerRepository.findRankPageBySnapshot(10L, 0, 100)).thenReturn(List.of(rank));
        when(crawlerRefreshPolicyService.evaluateRankSnapshot(snapshot.getSnapshotTime())).thenReturn(
            new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
                CrawlerRefreshPolicyService.FRESHNESS_FRESH,
                1L,
                false,
                false,
                java.time.Instant.now(),
                snapshot.getSnapshotTime().atZone(java.time.ZoneOffset.UTC).toInstant()
            )
        );

        RankPageVO vo = crawlerService.getRankPage("fanqie", "male-new", "urban-brain", 1, 999);

        assertThat(vo.getPage()).isEqualTo(1);
        assertThat(vo.getPageSize()).isEqualTo(100);
        assertThat(vo.getTotal()).isEqualTo(1);
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getBookName()).isEqualTo("Book 01");
        verify(crawlerRepository).findRankPageBySnapshot(10L, 0, 100);
    }

    @Test
    void shouldRejectForceOnLegacyRankReadBeforeFetchingOrWriting() {
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("male-hot-a");
        request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_FORCE);
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode(CrawlerRankRequest.REFRESH_MODE_FORCE))
            .thenReturn(CrawlerRankRequest.REFRESH_MODE_FORCE);

        assertThatThrownBy(() -> crawlerService.getRank(request))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .hasMessageContaining("/rank/refresh");

        verify(crawlerRepository, never()).findLatestRankSnapshot(anyString(), anyString());
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyInt());
        verify(crawlerRepository, never()).saveRankItem(
            anyString(), anyString(), anyInt(), anyLong(), anyString(), anyString(),
            any(), any(), any(LocalDateTime.class)
        );
    }

    @Test
    void shouldRouteLegacyRankMissThroughGovernedBoardRefresh() {
        CrawlerService service = spy(serviceWithRealChapterPersistence());
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("male-hot-a");
        request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);
        RankRefreshResultVO refreshResult = new RankRefreshResultVO();
        refreshResult.setSnapshotId(10L);
        refreshResult.setTotal(1);
        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Book 1");
        rank.setPlatform("fanqie");
        rank.setCategory("male-hot-a");
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO))
            .thenReturn(CrawlerRankRequest.REFRESH_MODE_AUTO);
        when(crawlerRepository.findLatestRankSnapshot("fanqie", "male-hot-a")).thenReturn(List.of());
        when(crawlerRepository.findRankPageBySnapshot(10L, 0, 100)).thenReturn(List.of(rank));
        doReturn(refreshResult).when(service).refreshRankBoard(any(CrawlerRankRequest.class));

        List<?> result = service.getRank(request);

        assertThat(result).hasSize(1);
        ArgumentCaptor<CrawlerRankRequest> requestCaptor = ArgumentCaptor.forClass(CrawlerRankRequest.class);
        verify(service).refreshRankBoard(requestCaptor.capture());
        CrawlerRankRequest governed = requestCaptor.getValue();
        assertThat(governed.getChannelCode()).isEqualTo("male-read");
        assertThat(governed.getBoardCode()).isEqualTo("1141");
        assertThat(governed.getIdempotencyKey()).startsWith("rank-refresh-generated:");
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldReuseFreshLegacyRankSnapshotWithoutAnyRefreshWork() {
        CrawlerService service = spy(serviceWithRealChapterPersistence());
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("male-new:262");
        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Fresh Book");
        rank.setPlatform("fanqie");
        rank.setCategory("male-new:262");
        rank.setCrawlTime(LocalDateTime.now().minusDays(2));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode(null))
            .thenReturn(CrawlerRankRequest.REFRESH_MODE_AUTO);
        when(crawlerRepository.findLatestRankSnapshot("fanqie", "male-new:262"))
            .thenReturn(List.of(rank));
        when(crawlerRefreshPolicyService.evaluateRankSnapshot(rank.getCrawlTime())).thenReturn(
            new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
                CrawlerRefreshPolicyService.FRESHNESS_FRESH,
                24L,
                false,
                false,
                java.time.Instant.now(),
                rank.getCrawlTime().atZone(java.time.ZoneOffset.UTC).toInstant()
            )
        );

        List<?> result = service.getRank(request);

        assertThat(result).hasSize(1);
        verify(service, never()).refreshRankBoard(any(CrawlerRankRequest.class));
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyInt());
        verify(asyncJobLockService, never()).tryAcquireStrict(anyString(), anyString(), anyLong());
        verify(crawlerCacheService, never()).getStrict(
            anyString(), eq(RankRefreshIdempotencyEntry.class));
    }

    @Test
    void shouldReturnStaleLegacyRankImmediatelyAndScheduleSingleBackgroundRefresh() throws Exception {
        CrawlerService service = spy(serviceWithRealChapterPersistence());
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("male-new:262");
        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Stale Book");
        rank.setPlatform("fanqie");
        rank.setCategory("male-new:262");
        rank.setCrawlTime(LocalDateTime.now().minusHours(100));
        CountDownLatch refreshStarted = new CountDownLatch(1);
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode(null))
            .thenReturn(CrawlerRankRequest.REFRESH_MODE_AUTO);
        when(crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()).thenReturn(false);
        when(crawlerRepository.findLatestRankSnapshot("fanqie", "male-new:262"))
            .thenReturn(List.of(rank));
        when(crawlerRefreshPolicyService.evaluateRankSnapshot(rank.getCrawlTime())).thenReturn(
            new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
                CrawlerRefreshPolicyService.FRESHNESS_STALE,
                100L,
                false,
                true,
                java.time.Instant.now(),
                rank.getCrawlTime().atZone(java.time.ZoneOffset.UTC).toInstant()
            )
        );
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        doAnswer(invocation -> {
            refreshStarted.countDown();
            return new RankRefreshResultVO();
        }).when(service).refreshRankBoard(any(CrawlerRankRequest.class));

        List<?> result = service.getRank(request);

        assertThat(result).hasSize(1);
        assertThat(((com.novelanalyzer.modules.crawler.vo.RankBookItemVO) result.get(0)).getBookName())
            .isEqualTo("Stale Book");
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyInt());
        verify(pythonCrawlerClient, never()).fetchRank(
            anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
        verify(asyncJobLockService, times(1)).tryAcquireStrict(
            eq("rank-stale-refresh:fanqie:male-new:262"),
            anyString(),
            anyLong()
        );
        assertThat(refreshStarted.await(3, TimeUnit.SECONDS)).isTrue();
        verify(service, times(1)).refreshRankBoard(any(CrawlerRankRequest.class));
        verify(asyncJobLockService, times(1)).release(
            eq("rank-stale-refresh:fanqie:male-new:262"),
            anyString()
        );
    }

    @Test
    void shouldSerializeConcurrentStaleLegacyRankBackgroundRefreshWithSingleflight() throws Exception {
        CrawlerService service = spy(serviceWithRealChapterPersistence());
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("male-new:262");
        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Stale Concurrent");
        rank.setPlatform("fanqie");
        rank.setCategory("male-new:262");
        rank.setCrawlTime(LocalDateTime.now().minusHours(96));
        AtomicInteger lockAttempts = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode(null))
            .thenReturn(CrawlerRankRequest.REFRESH_MODE_AUTO);
        when(crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()).thenReturn(false);
        when(crawlerRepository.findLatestRankSnapshot("fanqie", "male-new:262"))
            .thenReturn(List.of(rank));
        when(crawlerRefreshPolicyService.evaluateRankSnapshot(rank.getCrawlTime())).thenReturn(
            new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
                CrawlerRefreshPolicyService.FRESHNESS_STALE,
                96L,
                false,
                true,
                java.time.Instant.now(),
                rank.getCrawlTime().atZone(java.time.ZoneOffset.UTC).toInstant()
            )
        );
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            return lockAttempts.incrementAndGet() == 1;
        });
        doAnswer(invocation -> {
            refreshStarted.countDown();
            return new RankRefreshResultVO();
        }).when(service).refreshRankBoard(any(CrawlerRankRequest.class));

        List<?> first = service.getRank(request);
        List<?> second = service.getRank(request);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        verify(asyncJobLockService, times(2)).tryAcquireStrict(
            eq("rank-stale-refresh:fanqie:male-new:262"),
            anyString(),
            anyLong()
        );
        assertThat(refreshStarted.await(3, TimeUnit.SECONDS)).isTrue();
        verify(service, times(1)).refreshRankBoard(any(CrawlerRankRequest.class));
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldReturnExpiredLegacyRankAsHistoricalReferenceWhenRefreshFails() {
        CrawlerService service = spy(serviceWithRealChapterPersistence());
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("male-new:262");
        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Expired Book");
        rank.setPlatform("fanqie");
        rank.setCategory("male-new:262");
        rank.setCrawlTime(LocalDateTime.now().minusHours(200));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode(null))
            .thenReturn(CrawlerRankRequest.REFRESH_MODE_AUTO);
        when(crawlerRepository.findLatestRankSnapshot("fanqie", "male-new:262"))
            .thenReturn(List.of(rank));
        when(crawlerRefreshPolicyService.evaluateRankSnapshot(rank.getCrawlTime())).thenReturn(
            new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
                CrawlerRefreshPolicyService.FRESHNESS_EXPIRED,
                200L,
                true,
                true,
                java.time.Instant.now(),
                rank.getCrawlTime().atZone(java.time.ZoneOffset.UTC).toInstant()
            )
        );
        doThrow(new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "crawler down"))
            .when(service).refreshRankBoard(any(CrawlerRankRequest.class));

        List<?> result = service.getRank(request);

        assertThat(result).hasSize(1);
        assertThat(((com.novelanalyzer.modules.crawler.vo.RankBookItemVO) result.get(0)).getBookName())
            .isEqualTo("Expired Book");
        verify(service, times(1)).refreshRankBoard(any(CrawlerRankRequest.class));
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldAnnotateStaleRankPageAndScheduleSingleBackgroundRefresh() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(10L);
        snapshot.setSnapshotTime(LocalDateTime.now().minusHours(100));
        snapshot.setRecordCount(1);
        CrawlRankEntity rank = new CrawlRankEntity();
        rank.setBookId(100L);
        rank.setRankNo(1);
        rank.setBookName("Page Stale");
        rank.setAuthor("Author");
        rank.setPlatform("fanqie");
        rank.setCategory("urban-brain");
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(snapshot));
        when(crawlerRepository.findRankPageBySnapshot(10L, 0, 20)).thenReturn(List.of(rank));
        when(crawlerRefreshPolicyService.evaluateRankSnapshot(snapshot.getSnapshotTime())).thenReturn(
            new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
                CrawlerRefreshPolicyService.FRESHNESS_STALE,
                100L,
                false,
                true,
                java.time.Instant.now(),
                snapshot.getSnapshotTime().atZone(java.time.ZoneOffset.UTC).toInstant()
            )
        );
        when(crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()).thenReturn(false);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);

        RankPageVO vo = crawlerService.getRankPage("fanqie", "male-new", "urban-brain", 1, 20);

        assertThat(vo.getFreshness()).isEqualTo(CrawlerRefreshPolicyService.FRESHNESS_STALE);
        assertThat(vo.getHistoricalReference()).isFalse();
        assertThat(vo.getRefreshScheduled()).isTrue();
        assertThat(vo.getAgeHours()).isEqualTo(100L);
        verify(asyncJobLockService, times(1)).tryAcquireStrict(
            eq("rank-stale-refresh:fanqie:male-new:urban-brain"),
            anyString(),
            anyLong()
        );
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldRejectUnresolvableLegacyCategoryInsteadOfDirectFetching() {
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setPlatform("fanqie");
        request.setCategory("unsupported-legacy-category");
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode(null))
            .thenReturn(CrawlerRankRequest.REFRESH_MODE_AUTO);
        when(crawlerRepository.findLatestRankSnapshot("fanqie", "unsupported-legacy-category"))
            .thenReturn(List.of());

        assertThatThrownBy(() -> crawlerService.getRank(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("channelCode and boardCode");

        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyInt());
        verify(pythonCrawlerClient, never()).fetchRank(
            anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
    }

    @Test
    void shouldRejectRankRefreshWithoutIdempotencyKeyBeforeAnyRefreshWork() {
        assertThatThrownBy(() -> crawlerService.refreshRankBoard(rankRefreshRequest(7L, 91L, " ")))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .hasMessageContaining("idempotencyKey");

        verify(crawlerRankPersistenceService, never()).findCommittedResult(any());
        verify(crawlerCacheService, never()).getStrict(anyString(), eq(RankRefreshIdempotencyEntry.class));
        verify(crawlerRepository, never()).findRankBoard(anyString(), anyString(), anyString());
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldFailClosedWhenRankRefreshRedisClaimIsUnavailable() {
        CrawlerRankRequest request = rankRefreshRequest(7L, 91L, "redis-down");
        request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_FORCE);
        when(crawlerCacheService.getStrict(anyString(), eq(RankRefreshIdempotencyEntry.class)))
            .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> crawlerService.refreshRankBoard(request))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(pythonCrawlerClient);
        verify(asyncJobLockService, never()).tryAcquireStrict(anyString(), anyString(), anyLong());
    }

    @Test
    void shouldReturnInProgressThenReuseCompletedResultForTheSameScopedIdempotencyKey() throws Exception {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(10L);
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setRecordCount(30);
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch allowOwnerToFinish = new CountDownLatch(1);

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenAnswer(invocation -> {
            ownerEntered.countDown();
            assertThat(allowOwnerToFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(snapshot);
        });
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(snapshot.getSnapshotTime())).thenReturn(true);

        CrawlerRankRequest request = rankRefreshRequest(7L, 91L, "same-key");
        CompletableFuture<RankRefreshResultVO> owner = CompletableFuture.supplyAsync(
            () -> service.refreshRankBoard(request)
        );
        assertThat(ownerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> duplicate = CompletableFuture.supplyAsync(() -> {
            try {
                service.refreshRankBoard(request);
                return null;
            } catch (RuntimeException ex) {
                return ex;
            }
        });

        try {
            assertThat(duplicate.get(2, TimeUnit.SECONDS))
                .isInstanceOf(CrawlerService.RankRefreshInProgressException.class)
                .hasMessage(CrawlerService.RANK_REFRESH_IN_PROGRESS);
        } finally {
            allowOwnerToFinish.countDown();
        }
        assertThat(owner.get(5, TimeUnit.SECONDS).getSnapshotId()).isEqualTo(10L);
        assertThat(service.refreshRankBoard(request).getSnapshotId()).isEqualTo(10L);
        verify(crawlerRepository, times(1)).findLatestBoardSnapshot(1L);
    }

    @Test
    void shouldFetchOnlyOnceForConcurrentDuplicateRefreshRequests() throws Exception {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity persistedSnapshot = new RankSnapshotEntity();
        persistedSnapshot.setId(10L);
        persistedSnapshot.setSnapshotTime(LocalDateTime.now());
        persistedSnapshot.setRecordCount(1);
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(1);
        item.setPlatformBookId("book-1");
        item.setBookName("Book 1");
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch allowFetchToFinish = new CountDownLatch(1);

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.empty());
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        when(crawlerRankPersistenceService.claimFencingToken(1L)).thenReturn(7L);
        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenAnswer(invocation -> {
                fetchEntered.countDown();
                assertThat(allowFetchToFinish.await(5, TimeUnit.SECONDS)).isTrue();
                return List.of(item);
            });
        when(crawlerRankPersistenceService.persistBoardSnapshot(
            any(CrawlerRankRequest.class),
            eq(board),
            eq("AUTO"),
            anyString(),
            any(),
            any(LocalDateTime.class),
            any(),
            eq(7L),
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        )).thenReturn(persistedSnapshot);

        CrawlerRankRequest request = rankRefreshRequest(7L, 91L, "fetch-once-key");
        CompletableFuture<RankRefreshResultVO> owner = CompletableFuture.supplyAsync(
            () -> service.refreshRankBoard(request)
        );
        assertThat(fetchEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> duplicate = CompletableFuture.supplyAsync(() -> {
            try {
                service.refreshRankBoard(request);
                return null;
            } catch (RuntimeException ex) {
                return ex;
            }
        });

        try {
            assertThat(duplicate.get(2, TimeUnit.SECONDS))
                .isInstanceOf(CrawlerService.RankRefreshInProgressException.class)
                .hasMessage(CrawlerService.RANK_REFRESH_IN_PROGRESS);
        } finally {
            allowFetchToFinish.countDown();
        }
        assertThat(owner.get(5, TimeUnit.SECONDS).getSnapshotId()).isEqualTo(10L);
        assertThat(service.refreshRankBoard(request).getSnapshotId()).isEqualTo(10L);
        verify(pythonCrawlerClient, times(1)).fetchRank(
            anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
        verify(crawlerRankPersistenceService, times(1)).persistBoardSnapshot(
            any(CrawlerRankRequest.class),
            eq(board),
            eq("AUTO"),
            anyString(),
            any(),
            any(LocalDateTime.class),
            any(),
            eq(7L),
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        );
    }

    @Test
    void shouldReuseFreshSnapshotAfterDifferentKeyAutoRequestsCompeteForBoardLock() throws Exception {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity staleSnapshot = rankSnapshot(10L, LocalDateTime.now().minusDays(4));
        RankSnapshotEntity freshSnapshot = rankSnapshot(11L, LocalDateTime.now());
        freshSnapshot.setRecordCount(30);
        AtomicReference<RankSnapshotEntity> canonicalSnapshot = new AtomicReference<>(staleSnapshot);
        AtomicInteger lockAttempts = new AtomicInteger();
        CountDownLatch firstFetchEntered = new CountDownLatch(1);
        CountDownLatch secondWaitingForLock = new CountDownLatch(1);
        CountDownLatch allowFirstFetch = new CountDownLatch(1);
        CountDownLatch firstReleasedLock = new CountDownLatch(1);
        ExternalRankItem item = rankItem();

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L))
            .thenAnswer(invocation -> Optional.of(canonicalSnapshot.get()));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(any(LocalDateTime.class)))
            .thenAnswer(invocation -> invocation.<LocalDateTime>getArgument(0).isAfter(LocalDateTime.now().minusDays(3)));
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            if (lockAttempts.incrementAndGet() == 1) {
                return true;
            }
            secondWaitingForLock.countDown();
            assertThat(firstReleasedLock.await(5, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        doAnswer(invocation -> {
            firstReleasedLock.countDown();
            return null;
        }).when(asyncJobLockService).release(anyString(), anyString());
        when(crawlerRankPersistenceService.claimFencingToken(1L)).thenReturn(7L, 8L);
        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenAnswer(invocation -> {
                firstFetchEntered.countDown();
                assertThat(allowFirstFetch.await(5, TimeUnit.SECONDS)).isTrue();
                return List.of(item);
            });
        when(crawlerRankPersistenceService.persistBoardSnapshot(
            any(CrawlerRankRequest.class), eq(board), eq("AUTO"), anyString(), any(),
            any(LocalDateTime.class), any(), anyLong(), any(CrawlerRankPersistenceService.IdempotencyContext.class)
        )).thenAnswer(invocation -> {
            canonicalSnapshot.set(freshSnapshot);
            return freshSnapshot;
        });

        CompletableFuture<RankRefreshResultVO> first = CompletableFuture.supplyAsync(
            () -> service.refreshRankBoard(rankRefreshRequest(7L, 91L, "auto-key-a"))
        );
        assertThat(firstFetchEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<RankRefreshResultVO> second = CompletableFuture.supplyAsync(
            () -> service.refreshRankBoard(rankRefreshRequest(7L, 91L, "auto-key-b"))
        );
        assertThat(secondWaitingForLock.await(5, TimeUnit.SECONDS)).isTrue();
        allowFirstFetch.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS).getSnapshotId()).isEqualTo(11L);
        RankRefreshResultVO secondResult = second.get(5, TimeUnit.SECONDS);
        assertThat(secondResult.getSnapshotId()).isEqualTo(11L);
        assertThat(secondResult.getReused()).isTrue();
        verify(pythonCrawlerClient, times(1)).fetchRank(
            anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
        verify(crawlerRankPersistenceService, times(1)).persistBoardSnapshot(
            any(CrawlerRankRequest.class), eq(board), eq("AUTO"), anyString(), any(),
            any(LocalDateTime.class), any(), anyLong(), any(CrawlerRankPersistenceService.IdempotencyContext.class)
        );
    }

    @Test
    void shouldRecheckForceQuotaAfterDifferentKeyRequestsCompeteForBoardLock() throws Exception {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity initialSnapshot = rankSnapshot(20L, LocalDateTime.now().minusDays(4));
        RankSnapshotEntity forcedSnapshot = rankSnapshot(21L, LocalDateTime.now());
        AtomicReference<RankSnapshotEntity> canonicalSnapshot = new AtomicReference<>(initialSnapshot);
        AtomicInteger successfulForceCount = new AtomicInteger();
        AtomicInteger lockAttempts = new AtomicInteger();
        CountDownLatch firstFetchEntered = new CountDownLatch(1);
        CountDownLatch secondWaitingForLock = new CountDownLatch(1);
        CountDownLatch allowFirstFetch = new CountDownLatch(1);
        CountDownLatch firstReleasedLock = new CountDownLatch(1);
        ExternalRankItem item = rankItem();

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L))
            .thenAnswer(invocation -> Optional.of(canonicalSnapshot.get()));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("FORCE")).thenReturn("FORCE");
        when(crawlerRefreshPolicyService.forceRefreshWindowStart()).thenReturn(LocalDateTime.now().minusDays(2));
        when(crawlerRepository.countRecentSuccessfulForceRefreshes(
            eq("fanqie"), eq("male-new"), eq("urban-brain"), any(LocalDateTime.class)
        )).thenAnswer(invocation -> successfulForceCount.get());
        when(crawlerRefreshPolicyService.allowForceRefresh(anyInt()))
            .thenAnswer(invocation -> invocation.<Integer>getArgument(0) < 1);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            if (lockAttempts.incrementAndGet() == 1) {
                return true;
            }
            secondWaitingForLock.countDown();
            assertThat(firstReleasedLock.await(5, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        doAnswer(invocation -> {
            firstReleasedLock.countDown();
            return null;
        }).when(asyncJobLockService).release(anyString(), anyString());
        when(crawlerRankPersistenceService.claimFencingToken(1L)).thenReturn(17L, 18L);
        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenAnswer(invocation -> {
                firstFetchEntered.countDown();
                assertThat(allowFirstFetch.await(5, TimeUnit.SECONDS)).isTrue();
                return List.of(item);
            });
        when(crawlerRankPersistenceService.persistBoardSnapshot(
            any(CrawlerRankRequest.class), eq(board), eq("FORCE"), anyString(), any(),
            any(LocalDateTime.class), any(), anyLong(), any(CrawlerRankPersistenceService.IdempotencyContext.class)
        )).thenAnswer(invocation -> {
            successfulForceCount.incrementAndGet();
            canonicalSnapshot.set(forcedSnapshot);
            return forcedSnapshot;
        });

        CrawlerRankRequest firstRequest = rankRefreshRequest(7L, 91L, "force-key-a");
        firstRequest.setRefreshMode("FORCE");
        CrawlerRankRequest secondRequest = rankRefreshRequest(7L, 91L, "force-key-b");
        secondRequest.setRefreshMode("FORCE");
        CompletableFuture<RankRefreshResultVO> first = CompletableFuture.supplyAsync(
            () -> service.refreshRankBoard(firstRequest)
        );
        assertThat(firstFetchEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<RankRefreshResultVO> second = CompletableFuture.supplyAsync(
            () -> service.refreshRankBoard(secondRequest)
        );
        assertThat(secondWaitingForLock.await(5, TimeUnit.SECONDS)).isTrue();
        allowFirstFetch.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS).getSnapshotId()).isEqualTo(21L);
        RankRefreshResultVO secondResult = second.get(5, TimeUnit.SECONDS);
        assertThat(secondResult.getSnapshotId()).isEqualTo(21L);
        assertThat(secondResult.getReused()).isTrue();
        assertThat(secondResult.getRefreshLimited()).isTrue();
        verify(pythonCrawlerClient, times(1)).fetchRank(
            anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
        verify(crawlerRepository, times(2)).countRecentSuccessfulForceRefreshes(
            eq("fanqie"), eq("male-new"), eq("urban-brain"), any(LocalDateTime.class)
        );
    }

    @Test
    void shouldNotShareRankRefreshIdempotencyAcrossProjects() {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(10L);
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setRecordCount(30);

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(snapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(snapshot.getSnapshotTime())).thenReturn(true);

        service.refreshRankBoard(rankRefreshRequest(7L, 91L, "same-key"));
        service.refreshRankBoard(rankRefreshRequest(7L, 92L, "same-key"));

        verify(crawlerRepository, times(2)).findLatestBoardSnapshot(1L);
    }

    @Test
    void shouldRejectFirstRankRefreshWhenDistributedBoardLockIsBusy() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.empty());
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(asyncJobLockService.tryAcquireStrict(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(false);

        assertThatThrownBy(() -> crawlerService.refreshRankBoard(rankRefreshRequest(null, null, "busy-first-refresh")))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class);
        verify(pythonCrawlerClient, never()).fetchRank(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void shouldRejectBusyBoardLockWhenNoNewerSnapshotAppears() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity oldSnapshot = new RankSnapshotEntity();
        oldSnapshot.setId(10L);
        oldSnapshot.setSnapshotTime(LocalDateTime.now().minusDays(4));
        oldSnapshot.setRecordCount(30);
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(oldSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> crawlerService.refreshRankBoard(rankRefreshRequest(null, null, "busy-stale-refresh")))
            .isInstanceOf(CrawlerService.RankRefreshInProgressException.class)
            .hasMessage(CrawlerService.RANK_REFRESH_IN_PROGRESS);
        verify(pythonCrawlerClient, never()).fetchRank(
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyInt()
        );
    }

    @Test
    void shouldReuseSameFreshSnapshotWhenConcurrentAutoRefreshOwnsBoardLock() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity freshSnapshot = new RankSnapshotEntity();
        freshSnapshot.setId(10L);
        freshSnapshot.setSnapshotTime(LocalDateTime.now());
        freshSnapshot.setRecordCount(30);
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(freshSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(freshSnapshot.getSnapshotTime()))
            .thenReturn(true);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(false);

        RankRefreshResultVO result = crawlerService.refreshRankBoard(
            rankRefreshRequest(null, null, "busy-fresh-refresh")
        );

        assertThat(result.getSnapshotId()).isEqualTo(10L);
        assertThat(result.getReused()).isTrue();
        verify(pythonCrawlerClient, never()).fetchRank(
            anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
    }

    @Test
    void shouldFetchRequestedTopThirtyWhenFreshSnapshotOnlyHasTopTen() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity incompleteSnapshot = new RankSnapshotEntity();
        incompleteSnapshot.setId(10L);
        incompleteSnapshot.setSnapshotTime(LocalDateTime.now());
        incompleteSnapshot.setRecordCount(10);
        RankSnapshotEntity refreshedSnapshot = new RankSnapshotEntity();
        refreshedSnapshot.setId(11L);
        refreshedSnapshot.setSnapshotTime(LocalDateTime.now().plusSeconds(1));
        refreshedSnapshot.setRecordCount(30);
        List<ExternalRankItem> fetched = java.util.stream.IntStream.rangeClosed(1, 30)
            .mapToObj(index -> {
                ExternalRankItem item = new ExternalRankItem();
                item.setRankNo(index);
                item.setPlatformBookId("book-" + index);
                item.setBookName("Book " + index);
                return item;
            })
            .toList();

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(incompleteSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(incompleteSnapshot.getSnapshotTime()))
            .thenReturn(true);
        when(crawlerRankPersistenceService.claimFencingToken(1L)).thenReturn(7L);
        when(pythonCrawlerClient.fetchRank("fanqie", "male-new", "urban-brain", 30, 5))
            .thenReturn(fetched);
        when(crawlerRankPersistenceService.persistBoardSnapshot(
            any(CrawlerRankRequest.class),
            eq(board),
            eq("AUTO"),
            anyString(),
            any(),
            any(LocalDateTime.class),
            any(),
            eq(7L),
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        )).thenReturn(refreshedSnapshot);
        CrawlerRankRequest request = rankRefreshRequest(7L, 91L, "expand-fresh-top-ten");
        request.setRankFetchCount(30);

        RankRefreshResultVO result = crawlerService.refreshRankBoard(request);

        assertThat(result.getSnapshotId()).isEqualTo(11L);
        assertThat(result.getTotal()).isEqualTo(30);
        assertThat(result.getReused()).isFalse();
        verify(pythonCrawlerClient).fetchRank("fanqie", "male-new", "urban-brain", 30, 5);
    }

    @Test
    void shouldReturnDatabaseCommittedResultWhenRedisCompletionCasLosesOwnership() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity reusableSnapshot = new RankSnapshotEntity();
        reusableSnapshot.setId(10L);
        reusableSnapshot.setSnapshotTime(LocalDateTime.now());
        reusableSnapshot.setRecordCount(30);
        RankRefreshResultVO canonical = new RankRefreshResultVO();
        canonical.setChannelCode("male-new");
        canonical.setBoardCode("urban-brain");
        canonical.setSnapshotId(10L);
        canonical.setSnapshotTime(reusableSnapshot.getSnapshotTime());
        canonical.setTotal(30);
        canonical.setReused(Boolean.TRUE);

        when(crawlerRankPersistenceService.findCommittedResult(
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        )).thenReturn(null, canonical);
        when(crawlerCacheService.putIfAbsent(anyString(), any(RankRefreshIdempotencyEntry.class), anyLong()))
            .thenReturn(true);
        when(crawlerCacheService.compareAndSet(
            anyString(),
            any(RankRefreshIdempotencyEntry.class),
            any(RankRefreshIdempotencyEntry.class),
            anyLong()
        )).thenReturn(false);
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(reusableSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(reusableSnapshot.getSnapshotTime())).thenReturn(true);

        RankRefreshResultVO result = crawlerService.refreshRankBoard(rankRefreshRequest(7L, 91L, "same-key"));

        assertThat(result).isSameAs(canonical);
        verify(crawlerRankPersistenceService, times(2)).findCommittedResult(
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        );
        verify(crawlerCacheService, times(1)).getStrict(
            anyString(),
            eq(RankRefreshIdempotencyEntry.class)
        );
        verify(pythonCrawlerClient, never()).fetchRank(
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyInt()
        );
    }

    @Test
    void shouldReturnDatabaseCommittedResultWhenRedisCompletionCasIsUnavailable() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity reusableSnapshot = new RankSnapshotEntity();
        reusableSnapshot.setId(10L);
        reusableSnapshot.setSnapshotTime(LocalDateTime.now());
        reusableSnapshot.setRecordCount(30);
        RankRefreshResultVO canonical = new RankRefreshResultVO();
        canonical.setSnapshotId(10L);
        canonical.setSnapshotTime(reusableSnapshot.getSnapshotTime());
        canonical.setTotal(30);
        canonical.setReused(Boolean.TRUE);

        when(crawlerRankPersistenceService.findCommittedResult(
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        )).thenReturn(null, canonical);
        when(crawlerCacheService.putIfAbsent(anyString(), any(RankRefreshIdempotencyEntry.class), anyLong()))
            .thenReturn(true);
        when(crawlerCacheService.compareAndSet(
            anyString(),
            any(RankRefreshIdempotencyEntry.class),
            any(RankRefreshIdempotencyEntry.class),
            anyLong()
        )).thenThrow(new IllegalStateException("redis unavailable"));
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(reusableSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(reusableSnapshot.getSnapshotTime())).thenReturn(true);

        RankRefreshResultVO result = crawlerService.refreshRankBoard(rankRefreshRequest(7L, 91L, "same-key"));

        assertThat(result).isSameAs(canonical);
        verify(crawlerRankPersistenceService, times(2)).findCommittedResult(
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        );
    }

    @Test
    void shouldRejectDuplicateInProgressIdempotencyKeyImmediatelyWithoutPolling() throws Exception {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(10L);
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setRecordCount(30);
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch allowOwnerToFinish = new CountDownLatch(1);
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenAnswer(invocation -> {
            ownerEntered.countDown();
            assertThat(allowOwnerToFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(snapshot);
        });
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(snapshot.getSnapshotTime())).thenReturn(true);
        CrawlerRankRequest request = rankRefreshRequest(7L, 91L, "same-key");

        CompletableFuture<RankRefreshResultVO> owner = CompletableFuture.supplyAsync(
            () -> service.refreshRankBoard(request)
        );
        assertThat(ownerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> duplicate = CompletableFuture.supplyAsync(() -> {
            try {
                service.refreshRankBoard(request);
                return null;
            } catch (RuntimeException ex) {
                return ex;
            }
        });

        try {
            assertThat(duplicate.get(2, TimeUnit.SECONDS))
                .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
                .hasMessage("RANK_REFRESH_IN_PROGRESS");
            verify(crawlerRepository, times(1)).findLatestBoardSnapshot(1L);
        } finally {
            allowOwnerToFinish.countDown();
        }
        assertThat(owner.get(5, TimeUnit.SECONDS).getSnapshotId()).isEqualTo(10L);
    }

    @Test
    void shouldNotCollideRankRefreshFingerprintsAcrossDelimitersOrNullText() {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(10L);
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setRecordCount(30);
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban|brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(snapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(snapshot.getSnapshotTime())).thenReturn(true);
        CrawlerRankRequest first = rankRefreshRequest(7L, 91L, "collision-key");
        first.setBoardCode("urban|brain");
        first.setCategory("x");
        CrawlerRankRequest delimiterCollision = rankRefreshRequest(7L, 91L, "collision-key");
        delimiterCollision.setBoardCode("urban");
        delimiterCollision.setCategory("brain|x");

        service.refreshRankBoard(first);

        assertThatThrownBy(() -> service.refreshRankBoard(delimiterCollision))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .hasMessageContaining("different rank refresh arguments");

        CrawlerRankRequest nullValue = rankRefreshRequest(7L, 92L, "null-key");
        nullValue.setBoardCode("urban|brain");
        nullValue.setCategory(null);
        CrawlerRankRequest textNull = rankRefreshRequest(7L, 92L, "null-key");
        textNull.setBoardCode("urban|brain");
        textNull.setCategory("null");
        service.refreshRankBoard(nullValue);
        assertThatThrownBy(() -> service.refreshRankBoard(textNull))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .hasMessageContaining("different rank refresh arguments");
    }

    @Test
    void shouldMarkIdempotencyOwnershipUnhealthyWhenClaimRenewalFails() {
        RankRefreshIdempotencyEntry claim = RankRefreshIdempotencyEntry.inProgress(
            "fingerprint",
            "owner-token",
            System.currentTimeMillis()
        );
        AtomicBoolean healthy = new AtomicBoolean(true);
        when(crawlerCacheService.compareAndSet("claim-key", claim, claim, 600L)).thenReturn(false);

        crawlerService.renewRankRefreshClaim("claim-key", claim, healthy);

        assertThat(healthy).isFalse();
    }

    @Test
    void shouldSuppressStaleAutomaticRankRefreshUnderResourcePressure() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity staleSnapshot = new RankSnapshotEntity();
        staleSnapshot.setId(10L);
        staleSnapshot.setSnapshotTime(LocalDateTime.now().minusDays(4));
        staleSnapshot.setRecordCount(30);
        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(staleSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(staleSnapshot.getSnapshotTime())).thenReturn(false);
        when(crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()).thenReturn(true);

        assertThatThrownBy(() -> crawlerService.refreshRankBoard(
            rankRefreshRequest(null, null, "resource-pressure-auto")
        )).isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class)
            .satisfies(ex -> assertThat(
                ((com.novelanalyzer.common.exception.BusinessException) ex).getResultCode()
            ).isEqualTo(com.novelanalyzer.common.result.ResultCode.SERVICE_UNAVAILABLE));
        verify(pythonCrawlerClient, never()).fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldNotReturnOldSnapshotWhenForcedRankFetchFails() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity oldSnapshot = rankSnapshot(10L, LocalDateTime.now().minusDays(4));

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(oldSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("FORCE")).thenReturn("FORCE");
        when(crawlerRefreshPolicyService.forceRefreshWindowStart()).thenReturn(LocalDateTime.now().minusDays(2));
        when(crawlerRefreshPolicyService.allowForceRefresh(anyInt())).thenReturn(true);
        when(crawlerRankPersistenceService.claimFencingToken(1L)).thenReturn(7L);
        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenThrow(new IllegalStateException("crawler timed out"));
        CrawlerRankRequest request = rankRefreshRequest(7L, 91L, "force-fetch-failure");
        request.setRefreshMode("FORCE");

        assertThatThrownBy(() -> crawlerService.refreshRankBoard(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("crawler timed out");
        verify(crawlerRepository).saveRankRefreshTask(
            eq("fanqie"),
            eq("male-new"),
            eq("urban-brain"),
            eq("FORCE"),
            any(),
            eq(3),
            eq("crawler timed out"),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
    }

    @Test
    void shouldNotReturnOldSnapshotWhenTransactionalRankPersistenceFails() {
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity oldSnapshot = new RankSnapshotEntity();
        oldSnapshot.setId(10L);
        oldSnapshot.setSnapshotTime(LocalDateTime.now().minusDays(4));
        oldSnapshot.setRecordCount(30);
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(1);
        item.setPlatformBookId("book-1");
        item.setBookName("Book 1");

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(oldSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(oldSnapshot.getSnapshotTime())).thenReturn(false);
        when(asyncJobLockService.tryAcquireStrict(anyString(), anyString(), anyLong())).thenReturn(true);
        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(List.of(item));
        doThrow(new IllegalStateException("persistence failed"))
            .when(crawlerRankPersistenceService)
            .persistBoardSnapshot(
                any(CrawlerRankRequest.class),
                eq(board),
                eq("AUTO"),
                anyString(),
                any(),
                any(LocalDateTime.class),
                any(),
                anyLong(),
                nullable(CrawlerRankPersistenceService.IdempotencyContext.class)
            );

        assertThatThrownBy(() -> crawlerService.refreshRankBoard(rankRefreshRequest(null, null, "persistence-failure")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("persistence failed");
        verify(crawlerRepository).saveRankRefreshTask(
            eq("fanqie"),
            eq("male-new"),
            eq("urban-brain"),
            eq("AUTO"),
            any(),
            eq(3),
            eq("persistence failed"),
            any(LocalDateTime.class),
            any(LocalDateTime.class)
        );
    }

    @Test
    void shouldAllowSameIdempotencyKeyRetryAfterFailedOwnerReleasesClaim() {
        CrawlerCacheService atomicCache = inMemoryStrictCache();
        CrawlerService service = new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            atomicCache,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
        RankBoardEntity board = new RankBoardEntity();
        board.setId(1L);
        RankSnapshotEntity oldSnapshot = rankSnapshot(10L, LocalDateTime.now().minusDays(4));
        RankSnapshotEntity refreshedSnapshot = rankSnapshot(11L, LocalDateTime.now());
        refreshedSnapshot.setRecordCount(1);
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(1);
        item.setPlatformBookId("book-1");
        item.setBookName("Book 1");

        when(crawlerRepository.findRankBoard("fanqie", "male-new", "urban-brain"))
            .thenReturn(Optional.of(board));
        when(crawlerRepository.findLatestBoardSnapshot(1L)).thenReturn(Optional.of(oldSnapshot));
        when(crawlerRefreshPolicyService.normalizeRankRefreshMode("AUTO")).thenReturn("AUTO");
        when(crawlerRefreshPolicyService.shouldReuseRankSnapshot(oldSnapshot.getSnapshotTime())).thenReturn(false);
        when(crawlerRankPersistenceService.claimFencingToken(1L)).thenReturn(7L, 8L);
        when(pythonCrawlerClient.fetchRank(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(List.of(item));
        doThrow(new IllegalStateException("first persistence failed"))
            .doReturn(refreshedSnapshot)
            .when(crawlerRankPersistenceService)
            .persistBoardSnapshot(
                any(CrawlerRankRequest.class),
                eq(board),
                eq("AUTO"),
                anyString(),
                any(),
                any(LocalDateTime.class),
                any(),
                anyLong(),
                any(CrawlerRankPersistenceService.IdempotencyContext.class)
            );
        CrawlerRankRequest request = rankRefreshRequest(7L, 91L, "retry-after-failure");

        assertThatThrownBy(() -> service.refreshRankBoard(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("first persistence failed");

        assertThat(service.refreshRankBoard(request).getSnapshotId()).isEqualTo(11L);
        verify(pythonCrawlerClient, times(2)).fetchRank(
            anyString(), anyString(), anyString(), anyInt(), anyInt()
        );
        verify(crawlerRankPersistenceService, times(2)).persistBoardSnapshot(
            any(CrawlerRankRequest.class),
            eq(board),
            eq("AUTO"),
            anyString(),
            any(),
            any(LocalDateTime.class),
            any(),
            anyLong(),
            any(CrawlerRankPersistenceService.IdempotencyContext.class)
        );
    }

    private CrawlerService serviceWithRealChapterPersistence() {
        return new CrawlerService(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            crawlerCacheService,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService
        );
    }

    private CrawlBookEntity crawlBook(Long bookId) {
        CrawlBookEntity book = new CrawlBookEntity();
        book.setId(bookId);
        book.setPlatform("fanqie");
        book.setPlatformBookId(String.valueOf(bookId));
        book.setBookUrl("https://fanqienovel.com/page/" + bookId);
        return book;
    }

    private ExternalChapterItem externalChapter(int chapterNo) {
        ExternalChapterItem chapter = new ExternalChapterItem();
        chapter.setChapterNo(chapterNo);
        chapter.setChapterTitle("Chapter " + chapterNo);
        chapter.setContent("chapter-content-" + chapterNo);
        chapter.setSourceWordCount(chapter.getContent().length());
        return chapter;
    }

    private ChapterVO completeChapter(int chapterNo) {
        ChapterVO chapter = new ChapterVO();
        chapter.setBookId(42L);
        chapter.setChapterNo(chapterNo);
        chapter.setChapterTitle("Chapter " + chapterNo);
        chapter.setContent("chapter-content-" + chapterNo);
        chapter.setWordCount(chapter.getContent().length());
        chapter.setSourceWordCount(chapter.getContent().length());
        return chapter;
    }

    private CrawlerCacheService inMemoryStrictCache() {
        StringRedisTemplate unavailableRedis = mock(
            StringRedisTemplate.class,
            invocation -> {
                throw new RedisConnectionFailureException("redis unavailable");
            }
        );
        return new CrawlerCacheService(unavailableRedis, new ObjectMapper().findAndRegisterModules()) {
            private final ConcurrentHashMap<String, RankRefreshIdempotencyEntry> entries = new ConcurrentHashMap<>();

            @Override
            public <T> T getStrict(String key, Class<T> targetType) {
                return targetType.cast(entries.get(key));
            }

            @Override
            public boolean putIfAbsent(String key, Object value, long ttlSeconds) {
                return entries.putIfAbsent(key, (RankRefreshIdempotencyEntry) value) == null;
            }

            @Override
            public boolean compareAndSet(String key, Object expectedValue, Object updatedValue, long ttlSeconds) {
                return entries.replace(
                    key,
                    (RankRefreshIdempotencyEntry) expectedValue,
                    (RankRefreshIdempotencyEntry) updatedValue
                );
            }

            @Override
            public boolean evictIfValue(String key, Object expectedValue) {
                return entries.remove(key, (RankRefreshIdempotencyEntry) expectedValue);
            }
        };
    }

    private CrawlerRankRequest rankRefreshRequest(Long userId, Long projectId, String idempotencyKey) {
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setUserId(userId);
        request.setProjectId(projectId);
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setRefreshMode("AUTO");
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private CrawlerChapterRequest chapterRequest(Long bookId, int chapterCount) {
        CrawlerChapterRequest request = new CrawlerChapterRequest();
        request.setPlatform("fanqie");
        request.setBookId(bookId);
        request.setChapterCount(chapterCount);
        return request;
    }

    private RankSnapshotEntity rankSnapshot(long id, LocalDateTime snapshotTime) {
        RankSnapshotEntity snapshot = new RankSnapshotEntity();
        snapshot.setId(id);
        snapshot.setSnapshotTime(snapshotTime);
        snapshot.setRecordCount(1);
        return snapshot;
    }

    private ExternalRankItem rankItem() {
        ExternalRankItem item = new ExternalRankItem();
        item.setRankNo(1);
        item.setPlatformBookId("book-1");
        item.setBookName("Book 1");
        return item;
    }
}

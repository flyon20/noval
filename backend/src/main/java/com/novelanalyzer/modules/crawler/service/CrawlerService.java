package com.novelanalyzer.modules.crawler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobLockService;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookSearchItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankBoard;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.dto.CrawlerBookSearchRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.dto.UserRankPreferenceRequest;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankRefreshIdempotencyEntry;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.crawler.vo.BookDetailVO;
import com.novelanalyzer.modules.crawler.vo.BookSearchCandidateVO;
import com.novelanalyzer.modules.crawler.vo.ChapterRefreshResultVO;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardCatalogVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardStatusVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardOptionVO;
import com.novelanalyzer.modules.crawler.vo.RankBookItemVO;
import com.novelanalyzer.modules.crawler.vo.RankPageVO;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import com.novelanalyzer.modules.crawler.vo.UserRankPreferenceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CrawlerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlerService.class);
    private static final Pattern CHAPTER_TITLE_PATTERN = Pattern.compile("^第\\s*(\\d+)章");
    private static final Pattern LEGACY_RANK_SLUG_PATTERN = Pattern.compile("^(?:/?rank/)?([01])_([12])_(\\d+)$");
    private static final Map<String, LegacyRankBoardSelection> LEGACY_RANK_BOARD_ALIASES = Map.of(
        "male-hot-a", new LegacyRankBoardSelection("male-read", "1141"),
        "male-hot-b", new LegacyRankBoardSelection("male-read", "1140"),
        "male-new-a", new LegacyRankBoardSelection("male-new", "1141")
    );
    private static final long RANK_TTL_SECONDS = 3L * 24 * 3600;
    private static final long BOOK_TTL_SECONDS = 7L * 24 * 3600;
    private static final long CHAPTER_TTL_SECONDS = 30L * 24 * 3600;
    private static final List<Integer> SUPPORTED_CHAPTER_CACHE_COUNTS = List.of(1, 3, 5, 10);
    private static final int DEFAULT_CRAWLER_HTTP_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_CHAPTER_FETCH_WORKERS = 3;
    private static final int DEFAULT_RANK_FETCH_COUNT = 30;
    private static final int MIN_RANK_FETCH_COUNT = 10;
    private static final int MAX_RANK_FETCH_COUNT = 100;
    private static final int MAX_RANK_PAGE_SIZE = 100;
    private static final long RANK_REFRESH_LOCK_TTL_SECONDS = 120L;
    private static final long RANK_REFRESH_IDEMPOTENCY_TTL_SECONDS = 24L * 3600;
    private static final long RANK_REFRESH_IDEMPOTENCY_IN_PROGRESS_TTL_SECONDS = 10L * 60;
    public static final String RANK_REFRESH_IN_PROGRESS = "RANK_REFRESH_IN_PROGRESS";
    private static final long CHAPTER_FETCH_LOCK_TTL_SECONDS = 120L;
    public static final String CHAPTER_FETCH_IN_PROGRESS = "CHAPTER_FETCH_IN_PROGRESS";
    private static final ScheduledExecutorService CRAWLER_LOCK_RENEWER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "crawler-lock-renewer");
        thread.setDaemon(true);
        return thread;
    });

    private final PythonCrawlerClient pythonCrawlerClient;
    private final CrawlerRepository crawlerRepository;
    private final CrawlerRankPersistenceService crawlerRankPersistenceService;
    private final CrawlerCacheService crawlerCacheService;
    private final CrawlerRefreshPolicyService crawlerRefreshPolicyService;
    private final SystemConfigService systemConfigService;
    private final AsyncJobLockService asyncJobLockService;
    private final CrawlerChapterPersistenceService crawlerChapterPersistenceService;
    private final CrawlerFetchGuard crawlerFetchGuard;

    public static final class RankRefreshInProgressException extends BusinessException {

        public RankRefreshInProgressException() {
            super(ResultCode.CONFLICT, RANK_REFRESH_IN_PROGRESS);
        }
    }

    public static final class ChapterFetchInProgressException extends BusinessException {

        public ChapterFetchInProgressException() {
            super(ResultCode.CONFLICT, CHAPTER_FETCH_IN_PROGRESS);
        }
    }

    public CrawlerService(PythonCrawlerClient pythonCrawlerClient,
                          CrawlerRepository crawlerRepository,
                          CrawlerRankPersistenceService crawlerRankPersistenceService,
                          CrawlerCacheService crawlerCacheService,
                          CrawlerRefreshPolicyService crawlerRefreshPolicyService,
                          SystemConfigService systemConfigService,
                          AsyncJobLockService asyncJobLockService) {
        this(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            crawlerCacheService,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService,
            new CrawlerChapterPersistenceService(crawlerRepository),
            new CrawlerFetchGuard(1)
        );
    }

    public CrawlerService(PythonCrawlerClient pythonCrawlerClient,
                          CrawlerRepository crawlerRepository,
                          CrawlerRankPersistenceService crawlerRankPersistenceService,
                          CrawlerCacheService crawlerCacheService,
                          CrawlerRefreshPolicyService crawlerRefreshPolicyService,
                          SystemConfigService systemConfigService,
                          AsyncJobLockService asyncJobLockService,
                          CrawlerChapterPersistenceService crawlerChapterPersistenceService) {
        this(
            pythonCrawlerClient,
            crawlerRepository,
            crawlerRankPersistenceService,
            crawlerCacheService,
            crawlerRefreshPolicyService,
            systemConfigService,
            asyncJobLockService,
            crawlerChapterPersistenceService,
            new CrawlerFetchGuard(1)
        );
    }

    @Autowired
    public CrawlerService(PythonCrawlerClient pythonCrawlerClient,
                          CrawlerRepository crawlerRepository,
                          CrawlerRankPersistenceService crawlerRankPersistenceService,
                          CrawlerCacheService crawlerCacheService,
                          CrawlerRefreshPolicyService crawlerRefreshPolicyService,
                          SystemConfigService systemConfigService,
                          AsyncJobLockService asyncJobLockService,
                          CrawlerChapterPersistenceService crawlerChapterPersistenceService,
                          CrawlerFetchGuard crawlerFetchGuard) {
        this.pythonCrawlerClient = pythonCrawlerClient;
        this.crawlerRepository = crawlerRepository;
        this.crawlerRankPersistenceService = crawlerRankPersistenceService;
        this.crawlerCacheService = crawlerCacheService;
        this.crawlerRefreshPolicyService = crawlerRefreshPolicyService;
        this.systemConfigService = systemConfigService;
        this.asyncJobLockService = asyncJobLockService;
        this.crawlerChapterPersistenceService = crawlerChapterPersistenceService;
        this.crawlerFetchGuard = crawlerFetchGuard;
    }

    public List<RankBookItemVO> getRank(CrawlerRankRequest request) {
        if (request == null || (!request.hasLegacyCategory() && !request.hasBoardSelection())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "legacy rank endpoint requires category");
        }
        String refreshMode = crawlerRefreshPolicyService.normalizeRankRefreshMode(request.getRefreshMode());
        if (CrawlerRankRequest.REFRESH_MODE_FORCE.equals(refreshMode)) {
            throw new BusinessException(
                ResultCode.BAD_REQUEST,
                "FORCE rank read is not supported; use /api/crawler/rank/refresh"
            );
        }
        String category = resolveCategory(request);
        String cacheKey = "rank:" + request.getPlatform() + ":" + category;
        List<CrawlRankEntity> latestSnapshot = crawlerRepository.findLatestRankSnapshot(request.getPlatform(), category);
        CrawlerRefreshPolicyService.RankSnapshotEvaluation evaluation = resolveRankEvaluation(
            latestSnapshot.isEmpty() ? null : latestSnapshot.get(0).getCrawlTime()
        );

        if (!latestSnapshot.isEmpty() && evaluation.isFresh()) {
            List<RankBookItemVO> response = toRankVos(latestSnapshot);
            crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
            return response;
        }

        if (!latestSnapshot.isEmpty() && evaluation.isStale()) {
            boolean scheduled = scheduleBackgroundRankRefresh(request, latestSnapshot, refreshMode);
            LOGGER.info(
                "rank.stale_reuse platform={} category={} ageHours={} refreshScheduled={}",
                request.getPlatform(),
                category,
                evaluation.ageHours(),
                scheduled
            );
            List<RankBookItemVO> response = toRankVos(latestSnapshot);
            crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
            return response;
        }

        // EXPIRED or MISSING: never treat as current evidence without a refresh attempt.
        try {
            LegacyRankBoardSelection boardSelection = resolveLegacyRankBoardSelection(request, latestSnapshot);
            CrawlerRankRequest refreshRequest = copyRankRequest(request);
            refreshRequest.setChannelCode(boardSelection.channelCode());
            refreshRequest.setBoardCode(boardSelection.boardCode());
            refreshRequest.setRefreshMode(refreshMode);
            if (refreshRequest.getIdempotencyKey() == null || refreshRequest.getIdempotencyKey().isBlank()) {
                refreshRequest.setIdempotencyKey(CrawlerRankIdempotencyKeyFactory.generate(
                    "legacy-rank",
                    refreshRequest,
                    LocalDate.now(ZoneOffset.UTC).toString()
                ));
            }
            RankRefreshResultVO refreshResult = refreshRankBoard(refreshRequest);
            if (refreshResult.getSnapshotId() == null) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "rank refresh completed without snapshot");
            }
            List<CrawlRankEntity> refreshedSnapshot = crawlerRepository.findRankPageBySnapshot(
                refreshResult.getSnapshotId(),
                0,
                MAX_RANK_FETCH_COUNT
            );
            if (refreshedSnapshot.isEmpty() && !Integer.valueOf(0).equals(refreshResult.getTotal())) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "rank refresh completed without legacy rank rows");
            }
            List<RankBookItemVO> response = toRankVos(refreshedSnapshot);
            crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
            return response;
        } catch (RuntimeException ex) {
            if (!latestSnapshot.isEmpty() && evaluation.isExpired()) {
                LOGGER.warn(
                    "rank.expired_historical_fallback platform={} category={} ageHours={} reason={}",
                    request.getPlatform(),
                    category,
                    evaluation.ageHours(),
                    ex.getMessage()
                );
                return toRankVos(latestSnapshot);
            }
            throw ex;
        }
    }

    public List<RankBoardCatalogVO> getBoardCatalog(String platform) {
        List<RankBoardEntity> persistedBoards = crawlerRepository.findRankBoards(platform);
        if (!persistedBoards.isEmpty()) {
            return toBoardCatalogVosFromEntities(persistedBoards);
        }

        try {
            List<ExternalRankBoard> boards = syncBoardCatalog(platform);
            if (!boards.isEmpty()) {
                return toBoardCatalogVos(boards);
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("rank.boardCatalog fallback-db platform={} reason={}", platform, ex.getMessage());
        }
        return toBoardCatalogVosFromEntities(persistedBoards);
    }

    public List<RankBoardCatalogVO> getPersistedBoardCatalog(String platform) {
        return toBoardCatalogVosFromEntities(crawlerRepository.findRankBoards(platform));
    }

    public List<RankBoardCatalogVO> syncRankBoardCatalog(String platform) {
        return toBoardCatalogVos(syncBoardCatalog(platform));
    }

    public List<BookSearchCandidateVO> searchBooks(CrawlerBookSearchRequest request) {
        String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim();
        if (keyword.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "keyword is required");
        }
        int limit = request.getLimit() == null ? 10 : Math.min(Math.max(request.getLimit(), 1), 20);
        Map<String, BookSearchCandidateVO> candidates = new LinkedHashMap<>();
        List<CrawlBookEntity> localBooks = crawlerRepository.searchBooks(request.getPlatform(), keyword, limit);
        for (CrawlBookEntity book : localBooks) {
            putCandidate(candidates, toSearchCandidate(book));
        }
        if (candidates.size() >= limit) {
            return candidates.values().stream().limit(limit).toList();
        }

        List<ExternalBookSearchItem> externalBooks = pythonCrawlerClient.searchBooks(request.getPlatform(), keyword, limit);
        for (ExternalBookSearchItem item : externalBooks) {
            putCandidate(candidates, toSearchCandidate(request.getPlatform(), item));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return candidates.values().stream().limit(limit).toList();
    }

    public Long completeExternalBookCandidate(String platform,
                                              String platformBookId,
                                              String bookName,
                                              String author,
                                              String intro,
                                              String bookUrl,
                                              int chapterCount) {
        if (platform == null || platform.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "platform is required");
        }
        if (bookUrl == null || bookUrl.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "bookUrl is required");
        }
        String normalizedPlatform = platform.trim();
        String normalizedBookUrl = bookUrl.trim();
        ExternalBookDetail detail = pythonCrawlerClient.fetchBook(
            normalizedPlatform,
            normalizedBookUrl,
            resolveCrawlerHttpTimeoutSeconds()
        );
        Long bookId = crawlerRepository.saveOrUpdateBook(
            normalizedPlatform,
            firstNonBlank(detail.getPlatformBookId(), platformBookId),
            firstNonBlank(detail.getBookName(), bookName),
            firstNonBlank(detail.getAuthor(), author),
            firstNonBlank(detail.getIntro(), intro),
            firstNonBlank(detail.getBookUrl(), normalizedBookUrl)
        );

        CrawlerChapterRequest chapterRequest = new CrawlerChapterRequest();
        chapterRequest.setPlatform(normalizedPlatform);
        chapterRequest.setBookId(bookId);
        chapterRequest.setChapterCount(Math.min(Math.max(chapterCount, 1), 10));
        getChapters(chapterRequest);
        return bookId;
    }

    public UserRankPreferenceVO getUserRankPreference(String platform) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return crawlerRepository.findUserRankPreference(authUser.getUserId(), platform)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "user rank preference not found"));
    }

    public UserRankPreferenceVO saveUserRankPreference(UserRankPreferenceRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return crawlerRepository.saveUserRankPreference(
            authUser.getUserId(),
            request.getPlatform(),
            request.getChannelCode(),
            request.getBoardCode(),
            resolveRankFetchCount(request.getPlatform(), request.getRankFetchCount(), true)
        );
    }

    public RankRefreshResultVO refreshRankBoard(CrawlerRankRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "rank refresh request is required");
        }
        String idempotencyKey = request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
            ? null
            : request.getIdempotencyKey().trim();
        if (idempotencyKey == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "idempotencyKey is required for rank refresh");
        }
        String fingerprint = rankRefreshFingerprint(request);
        String idempotencyHash = sha256(rankRefreshIdempotencyScope(request) + "|" + idempotencyKey);
        String cacheKey = "rank-refresh-idempotency:" + idempotencyHash;
        CrawlerRankPersistenceService.IdempotencyContext idempotencyContext =
            new CrawlerRankPersistenceService.IdempotencyContext(idempotencyHash, fingerprint);
        RankRefreshResultVO committedResult = crawlerRankPersistenceService.findCommittedResult(idempotencyContext);
        if (committedResult != null) {
            return committedResult;
        }
        RankRefreshIdempotencyEntry cached = readRankRefreshIdempotencyEntry(cacheKey);
        if (cached == null) {
            RankRefreshIdempotencyEntry candidate = newRankRefreshClaim(fingerprint);
            if (putRankRefreshClaim(cacheKey, candidate)) {
                return executeClaimedRankRefresh(request, cacheKey, candidate, idempotencyContext);
            }
            cached = readRankRefreshIdempotencyEntry(cacheKey);
        }
        if (cached != null) {
            validateRankRefreshFingerprint(fingerprint, cached);
            if (RankRefreshIdempotencyEntry.STATUS_SUCCEEDED.equals(cached.getStatus())
                && cached.getResult() != null) {
                RankRefreshResultVO committed = crawlerRankPersistenceService.findCommittedResult(idempotencyContext);
                return committed == null ? cached.getResult() : committed;
            }
        }
        RankRefreshResultVO committed = crawlerRankPersistenceService.findCommittedResult(idempotencyContext);
        if (committed != null) {
            return committed;
        }
        throw new RankRefreshInProgressException();
    }

    private RankRefreshResultVO executeClaimedRankRefresh(CrawlerRankRequest request,
                                                          String cacheKey,
                                                          RankRefreshIdempotencyEntry claim,
                                                          CrawlerRankPersistenceService.IdempotencyContext idempotencyContext) {
        AtomicBoolean claimHealthy = new AtomicBoolean(true);
        ScheduledFuture<?> renewal = scheduleRankIdempotencyRenewal(cacheKey, claim, claimHealthy);
        try {
            RankRefreshResultVO result = refreshRankBoardOnce(
                request,
                claimHealthy::get,
                idempotencyContext
            );
            result = crawlerRankPersistenceService.commitReusedResult(idempotencyContext, result);
            requireRankRefreshOwnership(claimHealthy::get);
            renewal.cancel(false);
            RankRefreshIdempotencyEntry completed = RankRefreshIdempotencyEntry.succeeded(
                claim.getFingerprint(),
                result
            );
            try {
                if (!compareAndSetRankRefreshEntry(
                    cacheKey,
                    claim,
                    completed,
                    RANK_REFRESH_IDEMPOTENCY_TTL_SECONDS
                )) {
                    return requireCommittedRankRefreshResult(idempotencyContext);
                }
            } catch (BusinessException ex) {
                RankRefreshResultVO committed = crawlerRankPersistenceService.findCommittedResult(idempotencyContext);
                if (committed != null) {
                    LOGGER.warn("rank.refresh Redis completion unavailable; returning database commit key={}", cacheKey);
                    return committed;
                }
                throw ex;
            }
            return result;
        } catch (RuntimeException ex) {
            evictRankRefreshClaim(cacheKey, claim);
            throw ex;
        } finally {
            renewal.cancel(false);
        }
    }

    private ScheduledFuture<?> scheduleRankIdempotencyRenewal(String cacheKey,
                                                              RankRefreshIdempotencyEntry claim,
                                                              AtomicBoolean claimHealthy) {
        long intervalSeconds = Math.max(1L, RANK_REFRESH_IDEMPOTENCY_IN_PROGRESS_TTL_SECONDS / 4L);
        return CRAWLER_LOCK_RENEWER.scheduleAtFixedRate(
            () -> renewRankRefreshClaim(cacheKey, claim, claimHealthy),
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
    }

    void renewRankRefreshClaim(String cacheKey,
                               RankRefreshIdempotencyEntry claim,
                               AtomicBoolean claimHealthy) {
        try {
            if (!compareAndSetRankRefreshEntry(
                cacheKey,
                claim,
                claim,
                RANK_REFRESH_IDEMPOTENCY_IN_PROGRESS_TTL_SECONDS
            )) {
                claimHealthy.set(false);
                LOGGER.error("rank.refresh idempotency ownership lost key={}", cacheKey);
            }
        } catch (RuntimeException ex) {
            claimHealthy.set(false);
            LOGGER.error("rank.refresh idempotency renewal failed key={} reason={}", cacheKey, ex.getMessage());
        }
    }

    private RankRefreshIdempotencyEntry newRankRefreshClaim(String fingerprint) {
        return RankRefreshIdempotencyEntry.inProgress(
            fingerprint,
            java.util.UUID.randomUUID().toString(),
            System.currentTimeMillis()
        );
    }

    private void validateRankRefreshFingerprint(String fingerprint, RankRefreshIdempotencyEntry cached) {
        if (!Objects.equals(fingerprint, cached.getFingerprint())) {
            throw new BusinessException(
                ResultCode.BAD_REQUEST,
                "idempotency key reused with different rank refresh arguments"
            );
        }
    }

    private String rankRefreshIdempotencyScope(CrawlerRankRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        Long userId = authUser == null ? request.getUserId() : authUser.getUserId();
        String callerScope = userId == null ? "internal-service" : "user:" + userId;
        String projectScope = request.getProjectId() == null ? "project:none" : "project:" + request.getProjectId();
        return callerScope + "|" + projectScope;
    }

    private RankRefreshIdempotencyEntry readRankRefreshIdempotencyEntry(String cacheKey) {
        try {
            return crawlerCacheService.getStrict(cacheKey, RankRefreshIdempotencyEntry.class);
        } catch (IllegalStateException ex) {
            throw rankRefreshRedisUnavailable(ex);
        }
    }

    private boolean putRankRefreshClaim(String cacheKey, RankRefreshIdempotencyEntry claim) {
        try {
            return crawlerCacheService.putIfAbsent(
                cacheKey,
                claim,
                RANK_REFRESH_IDEMPOTENCY_IN_PROGRESS_TTL_SECONDS
            );
        } catch (IllegalStateException ex) {
            throw rankRefreshRedisUnavailable(ex);
        }
    }

    private boolean compareAndSetRankRefreshEntry(String cacheKey,
                                                  RankRefreshIdempotencyEntry expected,
                                                  RankRefreshIdempotencyEntry updated,
                                                  long ttlSeconds) {
        try {
            return crawlerCacheService.compareAndSet(cacheKey, expected, updated, ttlSeconds);
        } catch (IllegalStateException ex) {
            throw rankRefreshRedisUnavailable(ex);
        }
    }

    private void evictRankRefreshClaim(String cacheKey, RankRefreshIdempotencyEntry claim) {
        try {
            crawlerCacheService.evictIfValue(cacheKey, claim);
        } catch (IllegalStateException ex) {
            LOGGER.warn("rank.refresh idempotency release failed key={} reason={}", cacheKey, ex.getMessage());
        }
    }

    private BusinessException rankRefreshRedisUnavailable(IllegalStateException cause) {
        LOGGER.warn("rank.refresh strict Redis unavailable reason={}", cause.getMessage());
        return new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "rank refresh idempotency service unavailable");
    }

    private RankRefreshResultVO requireCommittedRankRefreshResult(
        CrawlerRankPersistenceService.IdempotencyContext idempotencyContext
    ) {
        RankRefreshResultVO committed = crawlerRankPersistenceService.findCommittedResult(idempotencyContext);
        if (committed != null) {
            return committed;
        }
        throw new BusinessException(
            ResultCode.SERVICE_UNAVAILABLE,
            "rank refresh idempotency ownership changed before completion"
        );
    }

    private LegacyRankBoardSelection resolveLegacyRankBoardSelection(CrawlerRankRequest request,
                                                                     List<CrawlRankEntity> latestSnapshot) {
        if (request.hasBoardSelection()) {
            return new LegacyRankBoardSelection(request.getChannelCode().trim(), request.getBoardCode().trim());
        }
        for (CrawlRankEntity item : latestSnapshot) {
            if (item.getChannelCode() != null && !item.getChannelCode().isBlank()
                && item.getBoardCode() != null && !item.getBoardCode().isBlank()) {
                return new LegacyRankBoardSelection(item.getChannelCode().trim(), item.getBoardCode().trim());
            }
        }
        String category = request.getCategory() == null ? "" : request.getCategory().trim();
        LegacyRankBoardSelection alias = LEGACY_RANK_BOARD_ALIASES.get(category);
        if (alias != null) {
            return alias;
        }
        int separator = category.indexOf(':');
        if (separator > 0 && separator < category.length() - 1) {
            return new LegacyRankBoardSelection(
                category.substring(0, separator).trim(),
                category.substring(separator + 1).trim()
            );
        }
        Matcher slugMatcher = LEGACY_RANK_SLUG_PATTERN.matcher(category);
        if (slugMatcher.matches()) {
            String channelCode = switch (slugMatcher.group(1) + slugMatcher.group(2)) {
                case "01" -> "female-new";
                case "02" -> "female-read";
                case "11" -> "male-new";
                case "12" -> "male-read";
                default -> null;
            };
            if (channelCode != null) {
                return new LegacyRankBoardSelection(channelCode, slugMatcher.group(3));
            }
        }
        throw new BusinessException(
            ResultCode.BAD_REQUEST,
            "legacy rank refresh requires channelCode and boardCode"
        );
    }

    private CrawlerRankRequest copyRankRequest(CrawlerRankRequest source) {
        CrawlerRankRequest target = new CrawlerRankRequest();
        target.setPlatform(source.getPlatform());
        target.setCategory(source.getCategory());
        target.setChannelCode(source.getChannelCode());
        target.setBoardCode(source.getBoardCode());
        target.setRefreshMode(source.getRefreshMode());
        target.setForceReason(source.getForceReason());
        target.setRankFetchCount(source.getRankFetchCount());
        target.setIdempotencyKey(source.getIdempotencyKey());
        target.setUserId(source.getUserId());
        target.setProjectId(source.getProjectId());
        target.setSupervisorAttestation(source.getSupervisorAttestation());
        return target;
    }

    private RankRefreshResultVO refreshRankBoardOnce(CrawlerRankRequest request,
                                                     BooleanSupplier requestOwnershipHealthy,
                                                     CrawlerRankPersistenceService.IdempotencyContext idempotencyContext) {
        requireBoardSelection(request);
        String refreshMode = crawlerRefreshPolicyService.normalizeRankRefreshMode(request.getRefreshMode());
        int requestedRankFetchCount = resolveRankFetchCount(
            request.getPlatform(),
            request.getRankFetchCount(),
            true
        );
        RankBoardEntity board = ensureRankBoard(request.getPlatform(), request.getChannelCode(), request.getBoardCode());
        String lockKey = buildRankRefreshLockKey(request.getPlatform(), request.getChannelCode(), request.getBoardCode());
        String lockValue = java.util.UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = asyncJobLockService.tryAcquireStrict(lockKey, lockValue, RANK_REFRESH_LOCK_TTL_SECONDS);
        } catch (IllegalStateException ex) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "rank refresh lock service unavailable");
        }
        if (!acquired) {
            RankSnapshotEntity latestSnapshot = crawlerRepository.findLatestBoardSnapshot(board.getId()).orElse(null);
            if (latestSnapshot != null
                && CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode)
                && crawlerRefreshPolicyService.shouldReuseRankSnapshot(latestSnapshot.getSnapshotTime())
                && resolveSnapshotTotal(latestSnapshot) >= requestedRankFetchCount) {
                return crawlerRankPersistenceService.commitReusedResult(
                    idempotencyContext,
                    toRefreshResult(
                        request.getChannelCode(),
                        request.getBoardCode(),
                        latestSnapshot,
                        true,
                        false
                    )
                );
            }
            throw new RankRefreshInProgressException();
        }
        AtomicBoolean lockHealthy = new AtomicBoolean(true);
        ScheduledFuture<?> renewal = scheduleRankLockRenewal(lockKey, lockValue, lockHealthy);
        try {
            // The lock closes the read/refresh race; all reuse and quota decisions must use its latest state.
            RankSnapshotEntity latestSnapshot = crawlerRepository.findLatestBoardSnapshot(board.getId()).orElse(null);
            if (latestSnapshot != null && CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode)
                && crawlerRefreshPolicyService.shouldReuseRankSnapshot(latestSnapshot.getSnapshotTime())
                && resolveSnapshotTotal(latestSnapshot) >= requestedRankFetchCount) {
                return crawlerRankPersistenceService.commitReusedResult(
                    idempotencyContext,
                    toRefreshResult(request.getChannelCode(), request.getBoardCode(), latestSnapshot, true, false)
                );
            }
            if (CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode)
                && crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()) {
                throw new BusinessException(
                    ResultCode.SERVICE_UNAVAILABLE,
                    "\u7cfb\u7edf\u8d44\u6e90\u7d27\u5f20\uff0c\u5df2\u6682\u505c\u81ea\u52a8\u699c\u5355\u5237\u65b0\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5"
                );
            }
            if (latestSnapshot != null && CrawlerRankRequest.REFRESH_MODE_FORCE.equals(refreshMode)) {
                int recentForceCount = crawlerRepository.countRecentSuccessfulForceRefreshes(
                    request.getPlatform(),
                    request.getChannelCode(),
                    request.getBoardCode(),
                    crawlerRefreshPolicyService.forceRefreshWindowStart()
                );
                if (!crawlerRefreshPolicyService.allowForceRefresh(recentForceCount)) {
                    return crawlerRankPersistenceService.commitReusedResult(
                        idempotencyContext,
                        toRefreshResult(request.getChannelCode(), request.getBoardCode(), latestSnapshot, true, true)
                    );
                }
            }
            long fencingToken = crawlerRankPersistenceService.claimFencingToken(board.getId());
            BooleanSupplier combinedOwnershipHealthy = () -> requestOwnershipHealthy.getAsBoolean()
                && lockHealthy.get();
            return fetchAndPersistBoardRank(
                request,
                board,
                refreshMode,
                latestSnapshot,
                combinedOwnershipHealthy,
                fencingToken,
                idempotencyContext
            );
        } finally {
            renewal.cancel(false);
            asyncJobLockService.release(lockKey, lockValue);
        }
    }

    private ScheduledFuture<?> scheduleRankLockRenewal(String lockKey,
                                                       String lockValue,
                                                       AtomicBoolean lockHealthy) {
        long intervalSeconds = Math.max(1L, RANK_REFRESH_LOCK_TTL_SECONDS / 4L);
        return CRAWLER_LOCK_RENEWER.scheduleAtFixedRate(() -> {
            try {
                if (!asyncJobLockService.renewStrict(lockKey, lockValue, RANK_REFRESH_LOCK_TTL_SECONDS)) {
                    lockHealthy.set(false);
                    LOGGER.error("rank.refresh lock ownership lost key={}", lockKey);
                }
            } catch (RuntimeException ex) {
                lockHealthy.set(false);
                LOGGER.error("rank.refresh lock renewal failed key={} reason={}", lockKey, ex.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private String rankRefreshFingerprint(CrawlerRankRequest request) {
        StringBuilder canonical = new StringBuilder(192);
        appendFingerprintField(canonical, "platform", request.getPlatform());
        appendFingerprintField(canonical, "channelCode", request.getChannelCode());
        appendFingerprintField(canonical, "boardCode", request.getBoardCode());
        appendFingerprintField(canonical, "category", request.getCategory());
        appendFingerprintField(canonical, "refreshMode", request.getRefreshMode());
        appendFingerprintField(canonical, "forceReason", request.getForceReason());
        appendFingerprintField(canonical, "rankFetchCount", request.getRankFetchCount());
        return sha256(canonical.toString());
    }

    private void appendFingerprintField(StringBuilder target, String name, Object value) {
        target.append(name.length()).append(':').append(name).append('=');
        if (value == null) {
            target.append("-1:");
        } else {
            String text = String.valueOf(value);
            target.append(text.length()).append(':').append(text);
        }
        target.append(';');
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public RankPageVO getRankPage(String platform,
                                  String channelCode,
                                  String boardCode,
                                  Integer page,
                                  Integer pageSize) {
        RankBoardEntity board = crawlerRepository.findRankBoard(platform, channelCode, boardCode)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "rank board not found"));
        RankSnapshotEntity snapshot = crawlerRepository.findLatestBoardSnapshot(board.getId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "rank snapshot not found"));
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_RANK_PAGE_SIZE);
        int offset = (safePage - 1) * safePageSize;
        List<RankBookItemVO> items = crawlerRepository.findRankPageBySnapshot(snapshot.getId(), offset, safePageSize).stream()
            .map(this::toRankVo)
            .toList();

        CrawlerRefreshPolicyService.RankSnapshotEvaluation evaluation =
            resolveRankEvaluation(snapshot.getSnapshotTime());
        boolean refreshScheduled = false;
        if (evaluation.isStale() && !crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()) {
            refreshScheduled = scheduleBackgroundBoardRefresh(platform, channelCode, boardCode);
        }
        RankPageVO vo = new RankPageVO();
        vo.setSnapshotId(snapshot.getId());
        vo.setSnapshotTime(snapshot.getSnapshotTime());
        vo.setTotal(resolveSnapshotTotal(snapshot));
        vo.setPage(safePage);
        vo.setPageSize(safePageSize);
        vo.setItems(items);
        vo.setFreshness(evaluation.freshness());
        vo.setAgeHours(evaluation.ageHours());
        vo.setHistoricalReference(evaluation.historicalReference());
        vo.setRefreshScheduled(refreshScheduled);
        return vo;
    }

    public RankBoardStatusVO getRankStatus(String platform,
                                           String channelCode,
                                           String boardCode) {
        RankBoardEntity board = crawlerRepository.findRankBoard(platform, channelCode, boardCode)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "rank board not found"));
        RankSnapshotEntity snapshot = crawlerRepository.findLatestBoardSnapshot(board.getId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "rank snapshot not found"));
        CrawlerRefreshPolicyService.RankSnapshotEvaluation evaluation =
            resolveRankEvaluation(snapshot.getSnapshotTime());
        boolean refreshScheduled = false;
        if (evaluation.isStale() && !crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()) {
            refreshScheduled = scheduleBackgroundBoardRefresh(platform, channelCode, boardCode);
        }

        RankBoardStatusVO vo = new RankBoardStatusVO();
        vo.setSnapshotId(snapshot.getId());
        vo.setSnapshotTime(snapshot.getSnapshotTime());
        vo.setTotal(resolveSnapshotTotal(snapshot));
        vo.setFreshness(evaluation.freshness());
        vo.setAgeHours(evaluation.ageHours());
        vo.setHistoricalReference(evaluation.historicalReference());
        vo.setRefreshScheduled(refreshScheduled);
        return vo;
    }



    private CrawlerRefreshPolicyService.RankSnapshotEvaluation resolveRankEvaluation(java.time.LocalDateTime snapshotTime) {
        CrawlerRefreshPolicyService.RankSnapshotEvaluation evaluation =
            crawlerRefreshPolicyService.evaluateRankSnapshot(snapshotTime);
        if (evaluation != null) {
            return evaluation;
        }
        // Compatibility path for older mocks/tests that only stub shouldReuseRankSnapshot.
        if (snapshotTime != null && crawlerRefreshPolicyService.shouldReuseRankSnapshot(snapshotTime)) {
            return new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
                CrawlerRefreshPolicyService.FRESHNESS_FRESH,
                0L,
                false,
                false,
                java.time.Instant.now(),
                snapshotTime.atZone(java.time.ZoneOffset.UTC).toInstant()
            );
        }
        if (snapshotTime == null) {
            return CrawlerRefreshPolicyService.RankSnapshotEvaluation.missing(java.time.Instant.now());
        }
        return new CrawlerRefreshPolicyService.RankSnapshotEvaluation(
            CrawlerRefreshPolicyService.FRESHNESS_EXPIRED,
            0L,
            true,
            true,
            java.time.Instant.now(),
            snapshotTime.atZone(java.time.ZoneOffset.UTC).toInstant()
        );
    }

    private boolean scheduleBackgroundRankRefresh(CrawlerRankRequest request,
                                                  List<CrawlRankEntity> latestSnapshot,
                                                  String refreshMode) {
        if (crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()) {
            return false;
        }
        LegacyRankBoardSelection boardSelection = resolveLegacyRankBoardSelection(request, latestSnapshot);
        return scheduleBackgroundBoardRefresh(
            request.getPlatform(),
            boardSelection.channelCode(),
            boardSelection.boardCode()
        );
    }

    private boolean scheduleBackgroundBoardRefresh(String platform, String channelCode, String boardCode) {
        if (crawlerRefreshPolicyService.shouldSuppressAutomaticRefresh()) {
            return false;
        }
        String lockKey = "rank-stale-refresh:" + platform + ":" + channelCode + ":" + boardCode;
        String lockValue = UUID.randomUUID().toString();
        boolean acquired = asyncJobLockService.tryAcquireStrict(lockKey, lockValue, RANK_REFRESH_LOCK_TTL_SECONDS);
        if (!acquired) {
            return false;
        }
        CrawlerRankRequest refreshRequest = new CrawlerRankRequest();
        refreshRequest.setPlatform(platform);
        refreshRequest.setChannelCode(channelCode);
        refreshRequest.setBoardCode(boardCode);
        refreshRequest.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);
        refreshRequest.setIdempotencyKey(CrawlerRankIdempotencyKeyFactory.generate(
            "stale-bg",
            refreshRequest,
            LocalDate.now(ZoneOffset.UTC).toString()
        ));
        CompletableFuture.runAsync(() -> {
            try {
                refreshRankBoard(refreshRequest);
            } catch (Exception ex) {
                LOGGER.warn(
                    "rank.stale_background_refresh_failed platform={} channel={} board={} reason={}",
                    platform,
                    channelCode,
                    boardCode,
                    ex.getMessage()
                );
            } finally {
                asyncJobLockService.release(lockKey, lockValue);
            }
        });
        return true;
    }

    public BookDetailVO getBookDetail(String platform, Long bookId) {
        String cacheKey = "book:" + platform + ":" + bookId;
        BookDetailVO cached = crawlerCacheService.get(cacheKey, BookDetailVO.class);
        if (cached != null) {
            return cached;
        }

        CrawlBookEntity book = crawlerRepository.findBookById(bookId)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        CrawlBookEntity persistedBook = book;
        if (!crawlerRefreshPolicyService.shouldReuseBookDetail(book.getLastCrawlTime()) || !hasBookDetail(book)) {
            persistedBook = refreshBookDetailWithRepair(platform, book);
        }
        BookDetailVO vo = toBookDetailVO(persistedBook);
        crawlerCacheService.put(cacheKey, vo, BOOK_TTL_SECONDS);
        return vo;
    }

    public List<ChapterVO> getChapterStatus(CrawlerChapterRequest request) {
        crawlerRepository.findBookById(request.getBookId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        return crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
    }

    public List<ChapterVO> getChapters(CrawlerChapterRequest request) {
        String cacheKey = "chapter:" + request.getBookId() + ":" + request.getChapterCount();
        List<ChapterVO> cached = crawlerCacheService.get(cacheKey, new TypeReference<List<ChapterVO>>() {
        });
        if (resolveReusablePrefixCount(cached) >= request.getChapterCount()) {
            return cached;
        }

        CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        List<ChapterVO> persistedChapters = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
        int reusablePrefixCount = resolveReusablePrefixCount(persistedChapters);
        if (reusablePrefixCount >= request.getChapterCount()) {
            crawlerCacheService.put(cacheKey, persistedChapters, CHAPTER_TTL_SECONDS);
            return persistedChapters;
        }

        String lockKey = buildChapterFetchLockKey(request.getPlatform(), request.getBookId());
        String lockValue = java.util.UUID.randomUUID().toString();
        boolean acquired = tryAcquireChapterLock(lockKey, lockValue);
        if (!acquired) {
            List<ChapterVO> completedByOwner = crawlerRepository.findChapters(
                request.getBookId(),
                request.getChapterCount()
            );
            if (resolveReusablePrefixCount(completedByOwner) >= request.getChapterCount()) {
                crawlerCacheService.put(cacheKey, completedByOwner, CHAPTER_TTL_SECONDS);
                return completedByOwner;
            }
            throw new ChapterFetchInProgressException();
        }

        AtomicBoolean lockHealthy = new AtomicBoolean(true);
        ScheduledFuture<?> renewal = scheduleChapterLockRenewal(lockKey, lockValue, lockHealthy);
        BooleanSupplier ownership = () -> renewChapterLock(lockKey, lockValue, lockHealthy);
        try {
            // A previous owner may have completed the missing prefix between our first read and lock acquisition.
            List<ChapterVO> lockedPersistedChapters = crawlerRepository.findChapters(
                request.getBookId(),
                request.getChapterCount()
            );
            int lockedReusablePrefixCount = resolveReusablePrefixCount(lockedPersistedChapters);
            if (lockedReusablePrefixCount >= request.getChapterCount()) {
                crawlerCacheService.put(cacheKey, lockedPersistedChapters, CHAPTER_TTL_SECONDS);
                return lockedPersistedChapters;
            }

            int fetchStartChapterNo = lockedReusablePrefixCount + 1;
            int missingChapterCount = request.getChapterCount() - lockedReusablePrefixCount;
            requireChapterLockOwnership(ownership);
            List<ExternalChapterItem> chapters;
            try (CrawlerFetchGuard.Lease ignored = crawlerFetchGuard.acquireChapter(AuthUserHolder.get())) {
                chapters = fetchChaptersWithRepair(
                    request.getPlatform(),
                    book,
                    fetchStartChapterNo,
                    missingChapterCount,
                    ownership
                );
            }
            crawlerChapterPersistenceService.persistFetchedChapters(
                request.getPlatform(),
                request.getBookId(),
                chapters,
                ownership
            );
            requireChapterLockOwnership(ownership);
            List<ChapterVO> result = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
            requireChapterLockOwnership(ownership);
            crawlerCacheService.put(cacheKey, result, CHAPTER_TTL_SECONDS);
            return result;
        } finally {
            renewal.cancel(false);
            asyncJobLockService.release(lockKey, lockValue);
        }
    }

    public ChapterRefreshResultVO refreshChapters(CrawlerChapterRequest request) {
        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }

        LocalDateTime startTime = LocalDateTime.now();
        int maxAllowedRefreshTimes = resolveChapterRefreshMaxAllowed(authUser);
        int usedRefreshTimes = crawlerRepository.countRecentSuccessfulChapterRefreshes(
            authUser.getUserId(),
            crawlerRefreshPolicyService.chapterForceRefreshWindowStart()
        );
        if (usedRefreshTimes >= maxAllowedRefreshTimes) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "chapter refresh limit exceeded");
        }

        String lockKey = buildChapterFetchLockKey(request.getPlatform(), request.getBookId());
        String lockValue = java.util.UUID.randomUUID().toString();
        boolean acquired = tryAcquireChapterLock(lockKey, lockValue);
        if (!acquired) {
            throw new ChapterFetchInProgressException();
        }

        AtomicBoolean lockHealthy = new AtomicBoolean(true);
        ScheduledFuture<?> renewal = scheduleChapterLockRenewal(lockKey, lockValue, lockHealthy);
        BooleanSupplier ownership = () -> renewChapterLock(lockKey, lockValue, lockHealthy);
        try {
            CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
            requireChapterLockOwnership(ownership);
            List<ExternalChapterItem> chapters;
            try (CrawlerFetchGuard.Lease ignored = crawlerFetchGuard.acquireChapter(authUser)) {
                chapters = fetchChaptersWithRepair(
                    request.getPlatform(),
                    book,
                    1,
                    request.getChapterCount(),
                    ownership
                );
            }
            crawlerChapterPersistenceService.persistForcedChapters(
                authUser.getUserId(),
                authUser.getUsername(),
                request.getPlatform(),
                request.getBookId(),
                request.getChapterCount(),
                chapters,
                startTime,
                ownership
            );
            requireChapterLockOwnership(ownership);
            evictChapterCaches(request.getBookId());
            List<ChapterVO> refreshedChapters = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
            requireChapterLockOwnership(ownership);
            crawlerCacheService.put(buildChapterCacheKey(request.getBookId(), request.getChapterCount()), refreshedChapters, CHAPTER_TTL_SECONDS);
            int latestUsedRefreshTimes = usedRefreshTimes + 1;
            return buildChapterRefreshResult(refreshedChapters, maxAllowedRefreshTimes, latestUsedRefreshTimes);
        } catch (RuntimeException ex) {
            if (lockHealthy.get() && renewChapterLock(lockKey, lockValue, lockHealthy)) {
                crawlerRepository.saveChapterRefreshTask(
                    authUser.getUserId(),
                    authUser.getUsername(),
                    request.getPlatform(),
                    request.getBookId(),
                    request.getChapterCount(),
                    3,
                    ex.getMessage(),
                    startTime,
                    LocalDateTime.now()
                );
            }
            throw ex;
        } finally {
            renewal.cancel(false);
            asyncJobLockService.release(lockKey, lockValue);
        }
    }

    private String buildRankRefreshLockKey(String platform, String channelCode, String boardCode) {
        return "lock:rank-refresh:" + platform + ":" + channelCode + ":" + boardCode;
    }

    private String buildChapterFetchLockKey(String platform, Long bookId) {
        return "lock:chapter-fetch:" + platform + ":" + bookId;
    }

    private boolean tryAcquireChapterLock(String lockKey, String lockValue) {
        try {
            return asyncJobLockService.tryAcquireStrict(lockKey, lockValue, CHAPTER_FETCH_LOCK_TTL_SECONDS);
        } catch (IllegalStateException ex) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "chapter fetch lock service unavailable");
        }
    }

    private ScheduledFuture<?> scheduleChapterLockRenewal(String lockKey,
                                                           String lockValue,
                                                           AtomicBoolean lockHealthy) {
        long intervalSeconds = Math.max(1L, CHAPTER_FETCH_LOCK_TTL_SECONDS / 4L);
        return CRAWLER_LOCK_RENEWER.scheduleAtFixedRate(
            () -> renewChapterLock(lockKey, lockValue, lockHealthy),
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
    }

    boolean renewChapterLock(String lockKey, String lockValue, AtomicBoolean lockHealthy) {
        if (!lockHealthy.get()) {
            return false;
        }
        try {
            boolean renewed = asyncJobLockService.renewStrict(
                lockKey,
                lockValue,
                CHAPTER_FETCH_LOCK_TTL_SECONDS
            );
            if (!renewed) {
                lockHealthy.set(false);
                LOGGER.error("chapter.fetch lock ownership lost key={}", lockKey);
            }
            return renewed;
        } catch (RuntimeException ex) {
            lockHealthy.set(false);
            LOGGER.error("chapter.fetch lock renewal failed key={} reason={}", lockKey, ex.getMessage());
            return false;
        }
    }

    private void requireChapterLockOwnership(BooleanSupplier ownershipHealthy) {
        if (ownershipHealthy == null || !ownershipHealthy.getAsBoolean()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "chapter fetch lock ownership lost");
        }
    }

    private int resolveReusablePrefixCount(List<ChapterVO> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return 0;
        }
        List<ChapterVO> orderedChapters = chapters.stream()
            .sorted(java.util.Comparator.comparing(ChapterVO::getChapterNo))
            .toList();
        int expectedChapterNo = 1;
        for (ChapterVO chapter : orderedChapters) {
            if (!Objects.equals(chapter.getChapterNo(), expectedChapterNo)) {
                return expectedChapterNo - 1;
            }
            Integer parsedChapterNumber = parseChapterNumber(chapter.getChapterTitle());
            if (parsedChapterNumber != null && !Objects.equals(parsedChapterNumber, expectedChapterNo)) {
                return expectedChapterNo - 1;
            }
            if (!isChapterContentComplete(chapter)) {
                return expectedChapterNo - 1;
            }
            expectedChapterNo++;
        }
        return expectedChapterNo - 1;
    }

    private boolean isChapterContentComplete(ChapterVO chapter) {
        if (chapter == null || chapter.getContent() == null || chapter.getContent().isBlank()) {
            return false;
        }
        Integer sourceWordCount = chapter.getSourceWordCount();
        if (sourceWordCount == null || sourceWordCount <= 0) {
            return false;
        }
        return normalizeChapterLength(chapter.getContent()) >= Math.floor(sourceWordCount * 0.9d);
    }

    private int normalizeChapterLength(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.replace("\r", "").replace("\n", "").trim().length();
    }

    private Integer parseChapterNumber(String chapterTitle) {
        if (chapterTitle == null || chapterTitle.isBlank()) {
            return null;
        }
        Matcher matcher = CHAPTER_TITLE_PATTERN.matcher(chapterTitle.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private RankRefreshResultVO fetchAndPersistBoardRank(CrawlerRankRequest request,
                                                         RankBoardEntity board,
                                                         String refreshMode,
                                                         RankSnapshotEntity latestSnapshot,
                                                         BooleanSupplier ownershipHealthy,
                                                         long fencingToken,
                                                         CrawlerRankPersistenceService.IdempotencyContext idempotencyContext) {
        LocalDateTime startTime = LocalDateTime.now();
        boolean persistenceStarted = false;
        try {
            int requestedRankFetchCount = resolveRankFetchCount(request.getPlatform(), request.getRankFetchCount(), true);
            List<ExternalRankItem> fetchedRankItems;
            try (CrawlerFetchGuard.Lease ignored = crawlerFetchGuard.acquireRank()) {
                fetchedRankItems = pythonCrawlerClient.fetchRank(
                    request.getPlatform(),
                    request.getChannelCode(),
                    request.getBoardCode(),
                    requestedRankFetchCount,
                    resolveCrawlerHttpTimeoutSeconds()
                );
            }
            List<ExternalRankItem> rankItems = limitRankItems(fetchedRankItems, requestedRankFetchCount);
            requireRankRefreshOwnership(ownershipHealthy);
            persistenceStarted = true;
            RankSnapshotEntity snapshot = crawlerRankPersistenceService.persistBoardSnapshot(
                request,
                board,
                refreshMode,
                resolveCategory(request),
                rankItems,
                startTime,
                ownershipHealthy,
                fencingToken,
                idempotencyContext
            );
            LOGGER.info("rank.refresh platform={} channelCode={} boardCode={} reused=false limited=false requestedCount={} total={}",
                request.getPlatform(), request.getChannelCode(), request.getBoardCode(), requestedRankFetchCount, rankItems.size());
            return toRefreshResult(request.getChannelCode(), request.getBoardCode(), snapshot, false, false);
        } catch (CrawlerRankPersistenceService.RankRefreshAlreadyCommittedException ex) {
            return ex.getResult();
        } catch (RuntimeException ex) {
            crawlerRepository.saveRankRefreshTask(
                request.getPlatform(),
                request.getChannelCode(),
                request.getBoardCode(),
                refreshMode,
                request.getForceReason(),
                3,
                truncateForStorage(ex.getMessage(), 500),
                startTime,
                LocalDateTime.now()
            );
            if (persistenceStarted || ownershipHealthy == null || !ownershipHealthy.getAsBoolean()) {
                throw ex;
            }
            if (latestSnapshot != null && CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode)) {
                LOGGER.warn("rank.refresh fallback platform={} channelCode={} boardCode={} reason={}",
                    request.getPlatform(), request.getChannelCode(), request.getBoardCode(), ex.getMessage());
                return toRefreshResult(request.getChannelCode(), request.getBoardCode(), latestSnapshot, true, false);
            }
            throw ex;
        }
    }

    private void requireRankRefreshOwnership(BooleanSupplier ownershipHealthy) {
        if (ownershipHealthy == null || !ownershipHealthy.getAsBoolean()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "rank refresh lock ownership lost");
        }
    }

    private String truncateForStorage(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private List<ExternalRankBoard> syncBoardCatalog(String platform) {
        List<ExternalRankBoard> boards;
        try (CrawlerFetchGuard.Lease ignored = crawlerFetchGuard.acquireRank()) {
            boards = pythonCrawlerClient.fetchBoardCatalog(platform, resolveCrawlerHttpTimeoutSeconds());
        }
        if (boards == null || boards.isEmpty()) {
            return List.of();
        }
        for (ExternalRankBoard board : boards) {
            crawlerRepository.saveOrUpdateRankBoard(
                platform,
                board.getChannelCode(),
                defaultIfBlank(board.getChannelName(), board.getChannelCode()),
                board.getBoardCode(),
                defaultIfBlank(board.getBoardName(), board.getBoardCode())
            );
        }
        return boards;
    }

    private RankBoardEntity ensureRankBoard(String platform, String channelCode, String boardCode) {
        RankBoardEntity existing = crawlerRepository.findRankBoard(platform, channelCode, boardCode).orElse(null);
        if (existing != null) {
            return existing;
        }
        syncBoardCatalog(platform);
        return crawlerRepository.findRankBoard(platform, channelCode, boardCode)
            .orElseGet(() -> crawlerRepository.saveOrUpdateRankBoard(platform, channelCode, channelCode, boardCode, boardCode));
    }

    private void requireBoardSelection(CrawlerRankRequest request) {
        if (!request.hasBoardSelection()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "channelCode and boardCode are required");
        }
    }

    private RankRefreshResultVO toRefreshResult(String channelCode,
                                                String boardCode,
                                                RankSnapshotEntity snapshot,
                                                boolean reused,
                                                boolean refreshLimited) {
        RankRefreshResultVO vo = new RankRefreshResultVO();
        vo.setChannelCode(channelCode);
        vo.setBoardCode(boardCode);
        vo.setSnapshotId(snapshot.getId());
        vo.setSnapshotTime(snapshot.getSnapshotTime());
        vo.setTotal(resolveSnapshotTotal(snapshot));
        vo.setReused(reused);
        vo.setRefreshLimited(refreshLimited);
        vo.setAnalysisTriggered(Boolean.FALSE);
        return vo;
    }

    private int resolveSnapshotTotal(RankSnapshotEntity snapshot) {
        if (snapshot.getRecordCount() != null && snapshot.getRecordCount() > 0) {
            return snapshot.getRecordCount();
        }
        return crawlerRepository.countRanksBySnapshot(snapshot.getId());
    }

    private List<RankBoardCatalogVO> toBoardCatalogVos(List<ExternalRankBoard> boards) {
        Map<String, RankBoardCatalogVO> channels = new LinkedHashMap<>();
        for (ExternalRankBoard board : boards) {
            RankBoardCatalogVO channel = channels.computeIfAbsent(board.getChannelCode(), key -> {
                RankBoardCatalogVO vo = new RankBoardCatalogVO();
                vo.setChannelCode(board.getChannelCode());
                vo.setChannelName(defaultIfBlank(board.getChannelName(), board.getChannelCode()));
                return vo;
            });
            RankBoardOptionVO boardVo = new RankBoardOptionVO();
            boardVo.setBoardCode(board.getBoardCode());
            boardVo.setBoardName(defaultIfBlank(board.getBoardName(), board.getBoardCode()));
            channel.getBoards().add(boardVo);
        }
        return new ArrayList<>(channels.values());
    }

    private List<RankBoardCatalogVO> toBoardCatalogVosFromEntities(List<RankBoardEntity> boards) {
        Map<String, RankBoardCatalogVO> channels = new LinkedHashMap<>();
        for (RankBoardEntity board : boards) {
            RankBoardCatalogVO channel = channels.computeIfAbsent(board.getChannelCode(), key -> {
                RankBoardCatalogVO vo = new RankBoardCatalogVO();
                vo.setChannelCode(board.getChannelCode());
                vo.setChannelName(defaultIfBlank(board.getDescription(), board.getChannelCode()));
                return vo;
            });
            RankBoardOptionVO boardVo = new RankBoardOptionVO();
            boardVo.setBoardCode(board.getBoardCode());
            boardVo.setBoardName(defaultIfBlank(board.getBoardName(), board.getBoardCode()));
            channel.getBoards().add(boardVo);
        }
        return new ArrayList<>(channels.values());
    }

    private void putCandidate(Map<String, BookSearchCandidateVO> candidates, BookSearchCandidateVO candidate) {
        if (candidate == null || candidate.getBookName() == null || candidate.getBookName().isBlank()) {
            return;
        }
        candidates.putIfAbsent(candidateKey(candidate), candidate);
    }

    private String candidateKey(BookSearchCandidateVO candidate) {
        if (candidate.getPlatformBookId() != null && !candidate.getPlatformBookId().isBlank()) {
            return "platformBookId:" + candidate.getPlatformBookId();
        }
        if (candidate.getBookUrl() != null && !candidate.getBookUrl().isBlank()) {
            return "bookUrl:" + candidate.getBookUrl();
        }
        return "name:" + candidate.getBookName() + ":" + Objects.toString(candidate.getAuthor(), "");
    }

    private BookSearchCandidateVO toSearchCandidate(CrawlBookEntity book) {
        BookSearchCandidateVO vo = new BookSearchCandidateVO();
        vo.setBookId(book.getId());
        vo.setPlatform(book.getPlatform());
        vo.setPlatformBookId(book.getPlatformBookId());
        vo.setBookName(book.getBookName());
        vo.setAuthor(book.getAuthor());
        vo.setIntro(book.getIntro());
        vo.setBookUrl(book.getBookUrl());
        vo.setLocal(true);
        vo.setContentType("novel");
        vo.setReadableNovel(Boolean.TRUE);
        return vo;
    }

    private BookSearchCandidateVO toSearchCandidate(String platform, ExternalBookSearchItem item) {
        BookSearchCandidateVO vo = new BookSearchCandidateVO();
        vo.setPlatform(platform);
        vo.setPlatformBookId(item.getPlatformBookId());
        vo.setBookName(item.getBookName());
        vo.setAuthor(item.getAuthor());
        vo.setIntro(item.getIntro());
        vo.setBookUrl(item.getBookUrl());
        vo.setLocal(false);
        vo.setContentType(defaultIfBlank(item.getContentType(), "novel"));
        vo.setReadableNovel(item.getReadableNovel() == null ? Boolean.TRUE : item.getReadableNovel());
        vo.setUnavailableReason(item.getUnavailableReason());
        return vo;
    }

    private BookDetailVO toBookDetailVO(CrawlBookEntity book) {
        BookDetailVO vo = new BookDetailVO();
        vo.setBookId(book.getId());
        vo.setPlatform(book.getPlatform());
        vo.setBookName(book.getBookName());
        vo.setAuthor(book.getAuthor());
        vo.setIntro(book.getIntro());
        vo.setBookUrl(book.getBookUrl());
        return vo;
    }

    private RankBookItemVO toRankVo(CrawlRankEntity item) {
        RankBookItemVO vo = new RankBookItemVO();
        vo.setBookId(item.getBookId());
        vo.setRankNo(item.getRankNo());
        vo.setBookName(item.getBookName());
        vo.setAuthor(item.getAuthor());
        vo.setIntro(item.getIntro());
        vo.setBookUrl(item.getBookUrl());
        vo.setPlatform(item.getPlatform());
        vo.setCategory(item.getCategory());
        return vo;
    }

    private List<RankBookItemVO> toRankVos(List<CrawlRankEntity> snapshot) {
        return snapshot.stream().map(this::toRankVo).toList();
    }

    private boolean hasBookDetail(CrawlBookEntity book) {
        return book.getBookName() != null && !book.getBookName().isBlank()
            && book.getBookUrl() != null && !book.getBookUrl().isBlank();
    }

    private CrawlBookEntity refreshBookDetailWithRepair(String platform, CrawlBookEntity book) {
        RuntimeException lastException = null;
        for (String candidateUrl : buildCandidateBookUrls(platform, book)) {
            try {
                ExternalBookDetail detail = pythonCrawlerClient.fetchBook(
                    platform,
                    candidateUrl,
                    resolveCrawlerHttpTimeoutSeconds()
                );
                Long persistedId = crawlerRepository.saveOrUpdateBook(
                    platform,
                    detail.getPlatformBookId() == null || detail.getPlatformBookId().isBlank()
                        ? book.getPlatformBookId()
                        : detail.getPlatformBookId(),
                    detail.getBookName(),
                    detail.getAuthor(),
                    detail.getIntro(),
                    detail.getBookUrl() == null || detail.getBookUrl().isBlank() ? candidateUrl : detail.getBookUrl()
                );
                return crawlerRepository.findBookById(persistedId)
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
            } catch (RuntimeException ex) {
                lastException = ex;
            }
        }
        throw lastException == null
            ? new BusinessException(ResultCode.BAD_REQUEST, "book detail fetch failed")
            : lastException;
    }

    private String resolveRepairedChapterBookUrl(String platform,
                                                 CrawlBookEntity book,
                                                 BooleanSupplier ownershipHealthy) {
        RuntimeException lastException = null;
        for (String candidateUrl : buildCandidateBookUrls(platform, book)) {
            try {
                requireChapterLockOwnership(ownershipHealthy);
                ExternalBookDetail detail = pythonCrawlerClient.fetchBook(
                    platform,
                    candidateUrl,
                    resolveCrawlerHttpTimeoutSeconds()
                );
                requireChapterLockOwnership(ownershipHealthy);
                return detail.getBookUrl() == null || detail.getBookUrl().isBlank()
                    ? candidateUrl
                    : detail.getBookUrl();
            } catch (RuntimeException ex) {
                lastException = ex;
            }
        }
        throw lastException == null
            ? new BusinessException(ResultCode.BAD_REQUEST, "book detail fetch failed")
            : lastException;
    }

    private List<ExternalChapterItem> fetchChaptersWithRepair(String platform,
                                                              CrawlBookEntity book,
                                                              Integer startChapterNo,
                                                              Integer chapterCount,
                                                              BooleanSupplier ownershipHealthy) {
        try {
            return pythonCrawlerClient.fetchChapters(
                platform,
                book.getBookUrl(),
                chapterCount,
                startChapterNo,
                resolveCrawlerHttpTimeoutSeconds(),
                resolveChapterFetchWorkers()
            );
        } catch (RuntimeException ex) {
            requireChapterLockOwnership(ownershipHealthy);
            String repairedBookUrl = resolveRepairedChapterBookUrl(platform, book, ownershipHealthy);
            requireChapterLockOwnership(ownershipHealthy);
            return pythonCrawlerClient.fetchChapters(
                platform,
                repairedBookUrl,
                chapterCount,
                startChapterNo,
                resolveCrawlerHttpTimeoutSeconds(),
                resolveChapterFetchWorkers()
            );
        }
    }

    private Integer resolveCrawlerHttpTimeoutSeconds() {
        int configured = systemConfigService.getIntValueOrDefault(
            "crawler.http.timeout-seconds",
            DEFAULT_CRAWLER_HTTP_TIMEOUT_SECONDS
        );
        return Math.max(5, configured);
    }

    private Integer resolveChapterFetchWorkers() {
        int configured = systemConfigService.getIntValueOrDefault(
            "crawler.chapter.fetch-workers",
            DEFAULT_CHAPTER_FETCH_WORKERS
        );
        return Math.min(Math.max(1, configured), 8);
    }

    private int resolveChapterRefreshMaxAllowed(AuthUser authUser) {
        if (authUser.hasAnyRole(java.util.Set.of("ADMIN"))) {
            return crawlerRefreshPolicyService.chapterForceRefreshAdminMaxTimes();
        }
        return crawlerRefreshPolicyService.chapterForceRefreshUserMaxTimes();
    }

    private ChapterRefreshResultVO buildChapterRefreshResult(List<ChapterVO> chapters,
                                                             int maxAllowedRefreshTimes,
                                                             int usedRefreshTimes) {
        ChapterRefreshResultVO vo = new ChapterRefreshResultVO();
        vo.setChapters(chapters);
        vo.setMaxAllowedRefreshTimes(maxAllowedRefreshTimes);
        vo.setUsedRefreshTimes(usedRefreshTimes);
        vo.setRemainingRefreshTimes(Math.max(0, maxAllowedRefreshTimes - usedRefreshTimes));
        vo.setWindowDays(crawlerRefreshPolicyService.chapterForceRefreshWindowDays());
        return vo;
    }

    private void evictChapterCaches(Long bookId) {
        for (Integer chapterCount : SUPPORTED_CHAPTER_CACHE_COUNTS) {
            crawlerCacheService.evict(buildChapterCacheKey(bookId, chapterCount));
        }
    }

    private String buildChapterCacheKey(Long bookId, Integer chapterCount) {
        return "chapter:" + bookId + ":" + chapterCount;
    }

    private List<String> buildCandidateBookUrls(String platform, CrawlBookEntity book) {
        List<String> candidates = new ArrayList<>();
        if (book.getBookUrl() != null && !book.getBookUrl().isBlank()) {
            candidates.add(book.getBookUrl());
        }
        if ("fanqie".equalsIgnoreCase(platform)
            && book.getPlatformBookId() != null
            && !book.getPlatformBookId().isBlank()) {
            String repairedUrl = "https://fanqienovel.com/page/" + book.getPlatformBookId();
            if (!candidates.contains(repairedUrl)) {
                candidates.add(repairedUrl);
            }
        }
        return candidates;
    }

    private String resolveCategory(CrawlerRankRequest request) {
        if (request.hasLegacyCategory()) {
            return request.getCategory();
        }
        return request.getChannelCode() + ":" + request.getBoardCode();
    }

    private int resolveRankFetchCount(String platform, Integer requestedRankFetchCount, boolean useUserPreferenceFallback) {
        Integer normalizedRequested = normalizeRankFetchCount(requestedRankFetchCount);
        if (normalizedRequested != null) {
            return normalizedRequested;
        }
        if (useUserPreferenceFallback) {
            AuthUser authUser = AuthUserHolder.get();
            if (authUser != null) {
                Integer preferredCount = crawlerRepository.findUserRankPreference(authUser.getUserId(), platform)
                    .map(UserRankPreferenceVO::getRankFetchCount)
                    .orElse(null);
                Integer normalizedPreferred = normalizeRankFetchCount(preferredCount);
                if (normalizedPreferred != null) {
                    return normalizedPreferred;
                }
            }
        }
        return DEFAULT_RANK_FETCH_COUNT;
    }

    private Integer normalizeRankFetchCount(Integer rankFetchCount) {
        if (rankFetchCount == null) {
            return null;
        }
        if (rankFetchCount < MIN_RANK_FETCH_COUNT || rankFetchCount > MAX_RANK_FETCH_COUNT) {
            return null;
        }
        if (rankFetchCount % 10 != 0) {
            return null;
        }
        return rankFetchCount;
    }

    private List<ExternalRankItem> limitRankItems(List<ExternalRankItem> rankItems, int rankFetchCount) {
        if (rankItems == null || rankItems.isEmpty()) {
            return List.of();
        }
        return rankItems.stream()
            .limit(rankFetchCount)
            .toList();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private record LegacyRankBoardSelection(String channelCode, String boardCode) {
    }
}

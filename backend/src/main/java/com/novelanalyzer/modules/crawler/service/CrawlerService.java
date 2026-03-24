package com.novelanalyzer.modules.crawler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankBoard;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.dto.UserRankPreferenceRequest;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.crawler.vo.BookDetailVO;
import com.novelanalyzer.modules.crawler.vo.ChapterRefreshResultVO;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardCatalogVO;
import com.novelanalyzer.modules.crawler.vo.RankBoardOptionVO;
import com.novelanalyzer.modules.crawler.vo.RankBookItemVO;
import com.novelanalyzer.modules.crawler.vo.RankPageVO;
import com.novelanalyzer.modules.crawler.vo.RankRefreshResultVO;
import com.novelanalyzer.modules.crawler.vo.UserRankPreferenceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CrawlerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlerService.class);
    private static final Pattern CHAPTER_TITLE_PATTERN = Pattern.compile("^第\\s*(\\d+)章");
    private static final long RANK_TTL_SECONDS = 3L * 24 * 3600;
    private static final long BOOK_TTL_SECONDS = 7L * 24 * 3600;
    private static final long CHAPTER_TTL_SECONDS = 30L * 24 * 3600;
    private static final List<Integer> SUPPORTED_CHAPTER_CACHE_COUNTS = List.of(1, 3, 5, 10);
    private static final int DEFAULT_CRAWLER_HTTP_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_CHAPTER_FETCH_WORKERS = 3;
    private static final int DEFAULT_RANK_FETCH_COUNT = 30;
    private static final int MIN_RANK_FETCH_COUNT = 10;
    private static final int MAX_RANK_FETCH_COUNT = 100;

    private final PythonCrawlerClient pythonCrawlerClient;
    private final CrawlerRepository crawlerRepository;
    private final CrawlerCacheService crawlerCacheService;
    private final CrawlerRefreshPolicyService crawlerRefreshPolicyService;
    private final SystemConfigService systemConfigService;

    public CrawlerService(PythonCrawlerClient pythonCrawlerClient,
                          CrawlerRepository crawlerRepository,
                          CrawlerCacheService crawlerCacheService,
                          CrawlerRefreshPolicyService crawlerRefreshPolicyService,
                          SystemConfigService systemConfigService) {
        this.pythonCrawlerClient = pythonCrawlerClient;
        this.crawlerRepository = crawlerRepository;
        this.crawlerCacheService = crawlerCacheService;
        this.crawlerRefreshPolicyService = crawlerRefreshPolicyService;
        this.systemConfigService = systemConfigService;
    }

    public List<RankBookItemVO> getRank(CrawlerRankRequest request) {
        if (!request.hasLegacyCategory()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "legacy rank endpoint requires category");
        }
        String refreshMode = crawlerRefreshPolicyService.normalizeRankRefreshMode(request.getRefreshMode());
        String cacheKey = "rank:" + request.getPlatform() + ":" + request.getCategory();
        List<RankBookItemVO> cached = crawlerCacheService.get(cacheKey, new TypeReference<List<RankBookItemVO>>() {
        });
        if (CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode) && cached != null) {
            return cached;
        }

        List<CrawlRankEntity> latestSnapshot = crawlerRepository.findLatestRankSnapshot(request.getPlatform(), request.getCategory());
        if (shouldReuseHistoricalSnapshot(refreshMode, request, latestSnapshot)) {
            List<RankBookItemVO> response = toRankVos(latestSnapshot);
            crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
            return response;
        }

        return fetchAndPersistLegacyRank(request, refreshMode, cacheKey, latestSnapshot);
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
        requireBoardSelection(request);
        String refreshMode = crawlerRefreshPolicyService.normalizeRankRefreshMode(request.getRefreshMode());
        RankBoardEntity board = ensureRankBoard(request.getPlatform(), request.getChannelCode(), request.getBoardCode());
        RankSnapshotEntity latestSnapshot = crawlerRepository.findLatestBoardSnapshot(board.getId()).orElse(null);
        if (latestSnapshot != null && CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode)
            && crawlerRefreshPolicyService.shouldReuseRankSnapshot(latestSnapshot.getSnapshotTime())) {
            return toRefreshResult(request.getChannelCode(), request.getBoardCode(), latestSnapshot, true, false);
        }
        if (latestSnapshot != null && CrawlerRankRequest.REFRESH_MODE_FORCE.equals(refreshMode)) {
            int recentForceCount = crawlerRepository.countRecentSuccessfulForceRefreshes(
                request.getPlatform(),
                request.getChannelCode(),
                request.getBoardCode(),
                crawlerRefreshPolicyService.forceRefreshWindowStart()
            );
            if (!crawlerRefreshPolicyService.allowForceRefresh(recentForceCount)) {
                return toRefreshResult(request.getChannelCode(), request.getBoardCode(), latestSnapshot, true, true);
            }
        }
        return fetchAndPersistBoardRank(request, board, refreshMode, latestSnapshot);
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
        int safePageSize = Math.max(pageSize, 1);
        int offset = (safePage - 1) * safePageSize;
        List<RankBookItemVO> items = crawlerRepository.findRankPageBySnapshot(snapshot.getId(), offset, safePageSize).stream()
            .map(this::toRankVo)
            .toList();

        RankPageVO vo = new RankPageVO();
        vo.setSnapshotId(snapshot.getId());
        vo.setSnapshotTime(snapshot.getSnapshotTime());
        vo.setTotal(resolveSnapshotTotal(snapshot));
        vo.setPage(safePage);
        vo.setPageSize(safePageSize);
        vo.setItems(items);
        return vo;
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

        int fetchStartChapterNo = reusablePrefixCount + 1;
        int missingChapterCount = request.getChapterCount() - reusablePrefixCount;
        List<ExternalChapterItem> chapters = fetchChaptersWithRepair(
            request.getPlatform(),
            book,
            fetchStartChapterNo,
            missingChapterCount
        );
        for (ExternalChapterItem chapter : chapters) {
            crawlerRepository.saveOrUpdateChapter(
                request.getPlatform(),
                request.getBookId(),
                chapter.getChapterNo(),
                chapter.getChapterTitle(),
                chapter.getContent(),
                chapter.getSourceWordCount()
            );
        }
        List<ChapterVO> result = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
        crawlerCacheService.put(cacheKey, result, CHAPTER_TTL_SECONDS);
        return result;
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

        try {
            CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
            List<ExternalChapterItem> chapters = fetchChaptersWithRepair(
                request.getPlatform(),
                book,
                1,
                request.getChapterCount()
            );
            for (ExternalChapterItem chapter : chapters) {
                crawlerRepository.saveOrUpdateChapter(
                    request.getPlatform(),
                    request.getBookId(),
                    chapter.getChapterNo(),
                    chapter.getChapterTitle(),
                    chapter.getContent(),
                    chapter.getSourceWordCount()
                );
            }
            evictChapterCaches(request.getBookId());
            List<ChapterVO> refreshedChapters = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
            crawlerCacheService.put(buildChapterCacheKey(request.getBookId(), request.getChapterCount()), refreshedChapters, CHAPTER_TTL_SECONDS);
            crawlerRepository.saveChapterRefreshTask(
                authUser.getUserId(),
                authUser.getUsername(),
                request.getPlatform(),
                request.getBookId(),
                request.getChapterCount(),
                2,
                null,
                startTime,
                LocalDateTime.now()
            );
            int latestUsedRefreshTimes = usedRefreshTimes + 1;
            return buildChapterRefreshResult(refreshedChapters, maxAllowedRefreshTimes, latestUsedRefreshTimes);
        } catch (RuntimeException ex) {
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
            throw ex;
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

    private List<RankBookItemVO> fetchAndPersistLegacyRank(CrawlerRankRequest request,
                                                           String refreshMode,
                                                           String cacheKey,
                                                           List<CrawlRankEntity> latestSnapshot) {
        LocalDateTime startTime = LocalDateTime.now();
        try {
            List<ExternalRankItem> rankItems = pythonCrawlerClient.fetchRank(
                request.getPlatform(),
                request.getCategory(),
                resolveCrawlerHttpTimeoutSeconds()
            );
            LocalDateTime snapshotTime = LocalDateTime.now();
            List<RankBookItemVO> response = new ArrayList<>();
            for (ExternalRankItem item : rankItems) {
                Long bookId = crawlerRepository.saveOrUpdateBook(
                    request.getPlatform(),
                    item.getPlatformBookId(),
                    item.getBookName(),
                    item.getAuthor(),
                    item.getIntro(),
                    item.getBookUrl()
                );
                crawlerRepository.saveRankItem(
                    request.getPlatform(),
                    request.getCategory(),
                    item.getRankNo(),
                    bookId,
                    item.getBookName(),
                    item.getBookUrl(),
                    item.getAuthor(),
                    item.getIntro(),
                    snapshotTime
                );
                response.add(toRankVo(item, bookId, request));
            }
            crawlerRepository.saveRankRefreshTask(
                request.getPlatform(),
                request.getCategory(),
                refreshMode,
                request.getForceReason(),
                2,
                null,
                startTime,
                LocalDateTime.now()
            );
            crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
            return response;
        } catch (RuntimeException ex) {
            crawlerRepository.saveRankRefreshTask(
                request.getPlatform(),
                request.getCategory(),
                refreshMode,
                request.getForceReason(),
                3,
                ex.getMessage(),
                startTime,
                LocalDateTime.now()
            );
            if (!latestSnapshot.isEmpty()) {
                List<RankBookItemVO> response = toRankVos(latestSnapshot);
                crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
                return response;
            }
            throw ex;
        }
    }

    private RankRefreshResultVO fetchAndPersistBoardRank(CrawlerRankRequest request,
                                                         RankBoardEntity board,
                                                         String refreshMode,
                                                         RankSnapshotEntity latestSnapshot) {
        LocalDateTime startTime = LocalDateTime.now();
        try {
            int requestedRankFetchCount = resolveRankFetchCount(request.getPlatform(), request.getRankFetchCount(), true);
            List<ExternalRankItem> rankItems = limitRankItems(pythonCrawlerClient.fetchRank(
                request.getPlatform(),
                request.getChannelCode(),
                request.getBoardCode(),
                requestedRankFetchCount,
                resolveCrawlerHttpTimeoutSeconds()
            ), requestedRankFetchCount);
            LocalDateTime snapshotTime = LocalDateTime.now();
            RankSnapshotEntity snapshot = crawlerRepository.saveRankSnapshot(board.getId(), snapshotTime, rankItems.size());
            for (ExternalRankItem item : rankItems) {
                Long bookId = crawlerRepository.saveOrUpdateBook(
                    request.getPlatform(),
                    item.getPlatformBookId(),
                    item.getBookName(),
                    item.getAuthor(),
                    item.getIntro(),
                    item.getBookUrl()
                );
                crawlerRepository.saveRankItem(
                    request.getPlatform(),
                    resolveCategory(request),
                    request.getChannelCode(),
                    request.getBoardCode(),
                    snapshot.getId(),
                    item.getRankNo(),
                    bookId,
                    item.getBookName(),
                    item.getBookUrl(),
                    item.getAuthor(),
                    item.getIntro(),
                    snapshotTime
                );
            }
            crawlerRepository.saveRankRefreshTask(
                request.getPlatform(),
                request.getChannelCode(),
                request.getBoardCode(),
                refreshMode,
                request.getForceReason(),
                2,
                null,
                startTime,
                LocalDateTime.now()
            );
            LOGGER.info("rank.refresh platform={} channelCode={} boardCode={} reused=false limited=false requestedCount={} total={}",
                request.getPlatform(), request.getChannelCode(), request.getBoardCode(), requestedRankFetchCount, rankItems.size());
            return toRefreshResult(request.getChannelCode(), request.getBoardCode(), snapshot, false, false);
        } catch (RuntimeException ex) {
            crawlerRepository.saveRankRefreshTask(
                request.getPlatform(),
                request.getChannelCode(),
                request.getBoardCode(),
                refreshMode,
                request.getForceReason(),
                3,
                ex.getMessage(),
                startTime,
                LocalDateTime.now()
            );
            if (latestSnapshot != null) {
                LOGGER.warn("rank.refresh fallback platform={} channelCode={} boardCode={} reason={}",
                    request.getPlatform(), request.getChannelCode(), request.getBoardCode(), ex.getMessage());
                return toRefreshResult(request.getChannelCode(), request.getBoardCode(), latestSnapshot, true, false);
            }
            throw ex;
        }
    }

    private List<ExternalRankBoard> syncBoardCatalog(String platform) {
        List<ExternalRankBoard> boards = pythonCrawlerClient.fetchBoardCatalog(platform, resolveCrawlerHttpTimeoutSeconds());
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

    private boolean shouldReuseHistoricalSnapshot(String refreshMode,
                                                  CrawlerRankRequest request,
                                                  List<CrawlRankEntity> latestSnapshot) {
        if (latestSnapshot.isEmpty()) {
            return false;
        }
        if (CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode)) {
            return crawlerRefreshPolicyService.shouldReuseRankSnapshot(latestSnapshot.get(0).getCrawlTime());
        }
        int recentForceCount = crawlerRepository.countRecentSuccessfulForceRefreshes(
            request.getPlatform(),
            request.getCategory(),
            crawlerRefreshPolicyService.forceRefreshWindowStart()
        );
        return !crawlerRefreshPolicyService.allowForceRefresh(recentForceCount);
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

    private RankBookItemVO toRankVo(ExternalRankItem item, Long bookId, CrawlerRankRequest request) {
        RankBookItemVO vo = new RankBookItemVO();
        vo.setBookId(bookId);
        vo.setRankNo(item.getRankNo());
        vo.setBookName(item.getBookName());
        vo.setAuthor(item.getAuthor());
        vo.setIntro(item.getIntro());
        vo.setBookUrl(item.getBookUrl());
        vo.setPlatform(request.getPlatform());
        vo.setCategory(request.getCategory());
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

    private List<ExternalChapterItem> fetchChaptersWithRepair(String platform,
                                                              CrawlBookEntity book,
                                                              Integer startChapterNo,
                                                              Integer chapterCount) {
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
            CrawlBookEntity repairedBook = refreshBookDetailWithRepair(platform, book);
            return pythonCrawlerClient.fetchChapters(
                platform,
                repairedBook.getBookUrl(),
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
}

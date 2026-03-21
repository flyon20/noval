package com.novelanalyzer.modules.crawler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.crawler.client.PythonCrawlerClient;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.dto.CrawlerChapterRequest;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.vo.BookDetailVO;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import com.novelanalyzer.modules.crawler.vo.RankBookItemVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class CrawlerService {

    private static final long RANK_TTL_SECONDS = 3L * 24 * 3600;
    private static final long BOOK_TTL_SECONDS = 7L * 24 * 3600;
    private static final long CHAPTER_TTL_SECONDS = 30L * 24 * 3600;

    private final PythonCrawlerClient pythonCrawlerClient;
    private final CrawlerRepository crawlerRepository;
    private final CrawlerCacheService crawlerCacheService;
    private final CrawlerRefreshPolicyService crawlerRefreshPolicyService;

    public CrawlerService(PythonCrawlerClient pythonCrawlerClient,
                          CrawlerRepository crawlerRepository,
                          CrawlerCacheService crawlerCacheService,
                          CrawlerRefreshPolicyService crawlerRefreshPolicyService) {
        this.pythonCrawlerClient = pythonCrawlerClient;
        this.crawlerRepository = crawlerRepository;
        this.crawlerCacheService = crawlerCacheService;
        this.crawlerRefreshPolicyService = crawlerRefreshPolicyService;
    }

    public List<RankBookItemVO> getRank(CrawlerRankRequest request) {
        String refreshMode = crawlerRefreshPolicyService.normalizeRankRefreshMode(request.getRefreshMode());
        String cacheKey = "rank:" + request.getPlatform() + ":" + request.getCategory();
        List<RankBookItemVO> cached = crawlerCacheService.get(cacheKey, new TypeReference<List<RankBookItemVO>>() {});
        if (CrawlerRankRequest.REFRESH_MODE_AUTO.equals(refreshMode) && cached != null) {
            return cached;
        }

        List<CrawlRankEntity> latestSnapshot = crawlerRepository.findLatestRankSnapshot(request.getPlatform(), request.getCategory());
        if (shouldReuseHistoricalSnapshot(refreshMode, request, latestSnapshot)) {
            List<RankBookItemVO> response = toRankVos(latestSnapshot);
            crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
            return response;
        }

        return fetchAndPersistRank(request, refreshMode, cacheKey, latestSnapshot);
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
        if (cached != null) {
            return cached;
        }

        CrawlBookEntity book = crawlerRepository.findBookById(request.getBookId())
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        List<ChapterVO> persistedChapters = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
        if (persistedChapters.size() >= request.getChapterCount()) {
            crawlerCacheService.put(cacheKey, persistedChapters, CHAPTER_TTL_SECONDS);
            return persistedChapters;
        }

        List<ExternalChapterItem> chapters = fetchChaptersWithRepair(request.getPlatform(), book, request.getChapterCount());
        for (ExternalChapterItem chapter : chapters) {
            crawlerRepository.saveOrUpdateChapter(
                request.getPlatform(),
                request.getBookId(),
                chapter.getChapterNo(),
                chapter.getChapterTitle(),
                chapter.getContent()
            );
        }
        List<ChapterVO> result = crawlerRepository.findChapters(request.getBookId(), request.getChapterCount());
        crawlerCacheService.put(cacheKey, result, CHAPTER_TTL_SECONDS);
        return result;
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

    private List<RankBookItemVO> fetchAndPersistRank(CrawlerRankRequest request,
                                                     String refreshMode,
                                                     String cacheKey,
                                                     List<CrawlRankEntity> latestSnapshot) {
        LocalDateTime startTime = LocalDateTime.now();
        try {
            List<ExternalRankItem> rankItems = pythonCrawlerClient.fetchRank(request.getPlatform(), request.getCategory());
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

    private List<RankBookItemVO> toRankVos(List<CrawlRankEntity> snapshot) {
        return snapshot.stream().map(item -> {
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
        }).toList();
    }

    private boolean hasBookDetail(CrawlBookEntity book) {
        return book.getBookName() != null && !book.getBookName().isBlank()
            && book.getBookUrl() != null && !book.getBookUrl().isBlank();
    }

    private CrawlBookEntity refreshBookDetailWithRepair(String platform, CrawlBookEntity book) {
        RuntimeException lastException = null;
        for (String candidateUrl : buildCandidateBookUrls(platform, book)) {
            try {
                ExternalBookDetail detail = pythonCrawlerClient.fetchBook(platform, candidateUrl);
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
                                                              Integer chapterCount) {
        try {
            return pythonCrawlerClient.fetchChapters(platform, book.getBookUrl(), chapterCount);
        } catch (RuntimeException ex) {
            CrawlBookEntity repairedBook = refreshBookDetailWithRepair(platform, book);
            return pythonCrawlerClient.fetchChapters(platform, repairedBook.getBookUrl(), chapterCount);
        }
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
}

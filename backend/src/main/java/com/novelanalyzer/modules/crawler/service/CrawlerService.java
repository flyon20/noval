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
import com.novelanalyzer.modules.crawler.repository.CrawlerRepository;
import com.novelanalyzer.modules.crawler.vo.BookDetailVO;
import com.novelanalyzer.modules.crawler.vo.ChapterVO;
import com.novelanalyzer.modules.crawler.vo.RankBookItemVO;
import org.springframework.stereotype.Service;

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

    public CrawlerService(PythonCrawlerClient pythonCrawlerClient,
                          CrawlerRepository crawlerRepository,
                          CrawlerCacheService crawlerCacheService) {
        this.pythonCrawlerClient = pythonCrawlerClient;
        this.crawlerRepository = crawlerRepository;
        this.crawlerCacheService = crawlerCacheService;
    }

    public List<RankBookItemVO> getRank(CrawlerRankRequest request) {
        String cacheKey = "rank:" + request.getPlatform() + ":" + request.getCategory();
        List<RankBookItemVO> cached = crawlerCacheService.get(cacheKey, new TypeReference<List<RankBookItemVO>>() {
        });
        if (cached != null) {
            return cached;
        }

        List<ExternalRankItem> rankItems = pythonCrawlerClient.fetchRank(request.getPlatform(), request.getCategory());
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
                item.getIntro()
            );
            RankBookItemVO vo = new RankBookItemVO();
            vo.setBookId(bookId);
            vo.setRankNo(item.getRankNo());
            vo.setBookName(item.getBookName());
            vo.setAuthor(item.getAuthor());
            vo.setIntro(item.getIntro());
            vo.setBookUrl(item.getBookUrl());
            vo.setPlatform(request.getPlatform());
            vo.setCategory(request.getCategory());
            response.add(vo);
        }
        crawlerCacheService.put(cacheKey, response, RANK_TTL_SECONDS);
        return response;
    }

    public BookDetailVO getBookDetail(String platform, Long bookId) {
        String cacheKey = "book:" + platform + ":" + bookId;
        BookDetailVO cached = crawlerCacheService.get(cacheKey, BookDetailVO.class);
        if (cached != null) {
            return cached;
        }

        CrawlBookEntity book = crawlerRepository.findBookById(bookId)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        ExternalBookDetail detail = pythonCrawlerClient.fetchBook(platform, book.getBookUrl());
        Long persistedId = crawlerRepository.saveOrUpdateBook(
            platform,
            detail.getPlatformBookId(),
            detail.getBookName(),
            detail.getAuthor(),
            detail.getIntro(),
            detail.getBookUrl() == null || detail.getBookUrl().isBlank() ? book.getBookUrl() : detail.getBookUrl()
        );

        CrawlBookEntity persistedBook = crawlerRepository.findBookById(persistedId)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
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
        List<ExternalChapterItem> chapters = pythonCrawlerClient.fetchChapters(
            request.getPlatform(),
            book.getBookUrl(),
            request.getChapterCount()
        );
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
}


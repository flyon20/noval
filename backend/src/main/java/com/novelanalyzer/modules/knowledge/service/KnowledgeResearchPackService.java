package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlChapterEntity;
import com.novelanalyzer.modules.knowledge.dto.BookResearchPackRequest;
import com.novelanalyzer.modules.knowledge.dto.RankLookupRequest;
import com.novelanalyzer.modules.knowledge.dto.RankResearchPackRequest;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.knowledge.vo.AnalysisMaterialVO;
import com.novelanalyzer.modules.knowledge.vo.BookProfileVO;
import com.novelanalyzer.modules.knowledge.vo.BookResearchPackVO;
import com.novelanalyzer.modules.knowledge.vo.ChapterMaterialVO;
import com.novelanalyzer.modules.knowledge.vo.RankLookupResultVO;
import com.novelanalyzer.modules.knowledge.vo.RankResearchPackVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class KnowledgeResearchPackService {

    private static final int DEFAULT_CHAPTER_LIMIT = 5;
    private static final int DEFAULT_ANALYSIS_LIMIT = 3;
    private static final int DEFAULT_RANK_LIMIT = 10;
    private static final int DEFAULT_CHAPTER_LIMIT_PER_BOOK = 2;
    private static final int CHAPTER_EXCERPT_LIMIT = 4000;
    private static final int ANALYSIS_EXCERPT_LIMIT = 4000;

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeRankToolService rankToolService;

    public KnowledgeResearchPackService(KnowledgeRepository knowledgeRepository,
                                        KnowledgeRankToolService rankToolService) {
        this.knowledgeRepository = knowledgeRepository;
        this.rankToolService = rankToolService;
    }

    public BookResearchPackVO buildBookPack(BookResearchPackRequest request) {
        CrawlBookEntity book = findRequestedBook(request)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        BookResearchPackVO pack = new BookResearchPackVO();
        pack.setBook(toBookProfile(book));
        pack.setChapters(toChapterExcerpts(knowledgeRepository.findChapters(
            book.getId(),
            normalizeLimit(request.getChapterLimit(), DEFAULT_CHAPTER_LIMIT, 20)
        )));
        pack.setRanks(knowledgeRepository.findLatestRankEvidenceForBook(book.getId()).stream()
            .map(this::toRankRow)
            .toList());
        pack.setAnalyses(knowledgeRepository.findLatestAnalysisResultsForBook(
                book.getId(),
                normalizeLimit(request.getAnalysisLimit(), DEFAULT_ANALYSIS_LIMIT, 20)
            ).stream()
            .map(this::toAnalysisExcerpt)
            .toList());
        return pack;
    }

    public RankResearchPackVO buildRankPack(RankResearchPackRequest request) {
        RankLookupRequest lookupRequest = new RankLookupRequest();
        lookupRequest.setPlatform(request.getPlatform());
        lookupRequest.setChannelCode(request.getChannelCode());
        lookupRequest.setBoardCode(request.getBoardCode());
        lookupRequest.setCategory(request.getCategory());
        lookupRequest.setRankNo(request.getRankNo());
        lookupRequest.setLimit(normalizeLimit(request.getLimit(), DEFAULT_RANK_LIMIT, 100));
        lookupRequest.setFreshness(request.getFreshness());
        lookupRequest.setAllowHistorical(request.getAllowHistorical());
        lookupRequest.setTimeWindowDays(request.getTimeWindowDays());
        lookupRequest.setRequireSnapshotTime(request.getRequireSnapshotTime());

        List<RankLookupResultVO> rankRows = rankToolService.lookupRank(lookupRequest);
        RankResearchPackVO pack = new RankResearchPackVO();
        pack.setRanks(rankRows);
        int chapterLimit = normalizeLimit(request.getChapterLimitPerBook(), DEFAULT_CHAPTER_LIMIT_PER_BOOK, 10);
        List<Long> bookIds = rankRows.stream()
            .map(RankLookupResultVO::getBookId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
        Map<Long, CrawlBookEntity> bookById = knowledgeRepository.findBooksByIds(bookIds).stream()
            .collect(Collectors.toMap(CrawlBookEntity::getId, book -> book, (left, right) -> left, LinkedHashMap::new));
        Map<Long, List<CrawlChapterEntity>> chaptersByBookId = groupByBookId(
            knowledgeRepository.findChaptersByBookIds(bookIds, chapterLimit),
            CrawlChapterEntity::getBookId
        );
        Map<Long, List<AnalysisResultEntity>> analysesByBookId = groupByBookId(
            knowledgeRepository.findLatestAnalysisResultsForBooks(bookIds, 1),
            AnalysisResultEntity::getBookId
        );
        List<BookProfileVO> books = new ArrayList<>();
        List<ChapterMaterialVO> chapters = new ArrayList<>();
        List<AnalysisMaterialVO> analyses = new ArrayList<>();
        for (RankLookupResultVO row : rankRows) {
            if (row.getBookId() == null) {
                continue;
            }
            BookProfileVO book = Optional.ofNullable(bookById.get(row.getBookId()))
                .map(this::toBookProfile)
                .orElseGet(() -> toBookProfile(row));
            book.setLatestRankNo(row.getRankNo());
            book.setLatestRankLabel(row.getSourceLabel());
            book.setCategory(row.getCategory());
            books.add(book);
            chapters.addAll(toChapterExcerpts(chaptersByBookId.getOrDefault(row.getBookId(), List.of())));
            analyses.addAll(analysesByBookId.getOrDefault(row.getBookId(), List.of()).stream()
                .map(this::toAnalysisExcerpt)
                .toList());
        }
        pack.setBooks(books);
        pack.setChapters(chapters);
        pack.setAnalyses(analyses);
        return pack;
    }

    private <T> Map<Long, List<T>> groupByBookId(List<T> items, java.util.function.Function<T, Long> bookIdReader) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        for (T item : items) {
            Long bookId = bookIdReader.apply(item);
            if (bookId == null) {
                continue;
            }
            grouped.computeIfAbsent(bookId, ignored -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private Optional<CrawlBookEntity> findRequestedBook(BookResearchPackRequest request) {
        if (request.getBookId() != null) {
            return knowledgeRepository.findBook(request.getBookId());
        }
        return knowledgeRepository.findBook(trimToNull(request.getPlatform()), trimToNull(request.getBookName()));
    }

    private BookProfileVO toBookProfile(CrawlBookEntity book) {
        BookProfileVO vo = new BookProfileVO();
        vo.setBookId(book.getId());
        vo.setPlatform(book.getPlatform());
        vo.setPlatformBookId(book.getPlatformBookId());
        vo.setBookName(book.getBookName());
        vo.setAuthor(book.getAuthor());
        vo.setIntro(book.getIntro());
        vo.setBookUrl(book.getBookUrl());
        return vo;
    }

    private BookProfileVO toBookProfile(RankLookupResultVO row) {
        BookProfileVO vo = new BookProfileVO();
        vo.setBookId(row.getBookId());
        vo.setPlatform(row.getPlatform());
        vo.setBookName(row.getBookName());
        vo.setAuthor(row.getAuthor());
        vo.setIntro(row.getIntro());
        vo.setCategory(row.getCategory());
        vo.setLatestRankNo(row.getRankNo());
        vo.setLatestRankLabel(row.getSourceLabel());
        return vo;
    }

    private List<ChapterMaterialVO> toChapterExcerpts(List<CrawlChapterEntity> chapters) {
        return chapters.stream().map(chapter -> {
            ChapterMaterialVO vo = new ChapterMaterialVO();
            vo.setChapterId(chapter.getId());
            vo.setSourceRefId(chapter.getId());
            vo.setBookId(chapter.getBookId());
            vo.setPlatform(chapter.getPlatform());
            vo.setChapterNo(chapter.getChapterNo());
            vo.setTitle(chapter.getChapterTitle());
            vo.setContent(truncate(chapter.getContent(), CHAPTER_EXCERPT_LIMIT));
            vo.setPreview(truncate(chapter.getContent(), 600));
            return vo;
        }).toList();
    }

    private RankLookupResultVO toRankRow(KnowledgeRepository.RankEvidence evidence) {
        RankLookupResultVO vo = new RankLookupResultVO();
        vo.setRankId(evidence.id());
        vo.setSnapshotId(evidence.snapshotId());
        vo.setSnapshotTime(evidence.snapshotTime());
        vo.setPlatform(evidence.platform());
        vo.setChannelCode(evidence.channelCode());
        vo.setBoardCode(evidence.boardCode());
        vo.setChannelName(evidence.channelName());
        vo.setBoardName(evidence.boardName());
        vo.setCategory(evidence.category());
        vo.setRankNo(evidence.rankNo());
        vo.setBookId(evidence.bookId());
        vo.setBookName(evidence.bookName());
        vo.setAuthor(evidence.author());
        vo.setIntro(evidence.intro());
        vo.setSourceLabel(buildRankSourceLabel(vo));
        return vo;
    }

    private AnalysisMaterialVO toAnalysisExcerpt(AnalysisResultEntity analysis) {
        AnalysisMaterialVO vo = new AnalysisMaterialVO();
        vo.setAnalysisId(analysis.getId());
        vo.setSourceRefId(analysis.getId());
        vo.setPlatform(analysis.getPlatform());
        vo.setBookId(analysis.getBookId());
        vo.setAnalysisType(analysis.getAnalysisType());
        vo.setTitle(analysis.getAnalysisType());
        String content = truncate(firstNonBlank(analysis.getResultContent(), analysis.getResultJson()), ANALYSIS_EXCERPT_LIMIT);
        vo.setContent(content);
        vo.setSummary(truncate(firstNonBlank(analysis.getResultJson(), analysis.getResultContent()), 600));
        vo.setPreview(truncate(content, 600));
        return vo;
    }

    private String buildRankSourceLabel(RankLookupResultVO vo) {
        String channel = firstNonBlank(vo.getChannelName(), vo.getChannelCode());
        String board = firstNonBlank(vo.getBoardName(), vo.getCategory(), vo.getBoardCode());
        String rank = vo.getRankNo() == null ? "" : " #" + vo.getRankNo();
        return (channel == null ? "rank" : channel) + " / " + (board == null ? "unknown board" : board) + rank;
    }

    private int normalizeLimit(Integer limit, int defaultValue, int max) {
        if (limit == null) {
            return defaultValue;
        }
        return Math.min(Math.max(limit, 1), max);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit);
    }
}

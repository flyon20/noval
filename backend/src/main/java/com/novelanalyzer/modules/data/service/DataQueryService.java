package com.novelanalyzer.modules.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.crawler.model.RankBoardEntity;
import com.novelanalyzer.modules.crawler.model.RankSnapshotEntity;
import com.novelanalyzer.modules.data.repository.DataQueryRepository;
import com.novelanalyzer.modules.data.vo.AnalysisHistoryItemVO;
import com.novelanalyzer.modules.data.vo.HotBookVO;
import com.novelanalyzer.modules.data.vo.InsightCardVO;
import com.novelanalyzer.modules.data.vo.RankSnapshotVO;
import com.novelanalyzer.modules.data.vo.SnapshotThemeComparisonVO;
import com.novelanalyzer.modules.data.vo.ThemeTableItemVO;
import com.novelanalyzer.modules.data.vo.ThemeWordCloudItemVO;
import com.novelanalyzer.modules.data.vo.VisualDataVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DataQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataQueryRepository dataQueryRepository;
    private final ObjectMapper objectMapper;

    public DataQueryService(DataQueryRepository dataQueryRepository, ObjectMapper objectMapper) {
        this.dataQueryRepository = dataQueryRepository;
        this.objectMapper = objectMapper;
    }

    public List<AnalysisHistoryItemVO> getHistory(String platform, Long bookId, String analysisType, Integer limit) {
        int size = normalizeLimit(limit, 20, 50);
        List<AnalysisResultEntity> results = dataQueryRepository.findHistory(platform, bookId, analysisType, size);
        Map<Long, CrawlBookEntity> bookMap = dataQueryRepository.findBookMap(
            results.stream().map(AnalysisResultEntity::getBookId).filter(Objects::nonNull).distinct().toList()
        );
        return results.stream().map(item -> toHistoryItem(item, bookMap.get(item.getBookId()))).toList();
    }

    public VisualDataVO getVisualData(String platform, String channelCode, String boardCode) {
        RankBoardEntity board = dataQueryRepository.findBoard(platform, channelCode, boardCode)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "rank board not found"));
        List<RankSnapshotEntity> snapshots = dataQueryRepository.findRecentSnapshots(board.getId(), 3);
        Map<Long, List<CrawlRankEntity>> ranksBySnapshot = dataQueryRepository.findRanksBySnapshotIds(
            snapshots.stream().map(RankSnapshotEntity::getId).toList()
        ).stream().collect(Collectors.groupingBy(
            CrawlRankEntity::getSnapshotId,
            LinkedHashMap::new,
            Collectors.toList()
        ));
        Map<String, Object> latestThemeResult = dataQueryRepository.findLatestBoardThemeResult(platform, channelCode, boardCode)
            .map(AnalysisResultEntity::getResultJson)
            .map(this::readResultJson)
            .orElse(Map.of());

        List<SnapshotThemeComparisonVO> snapshotComparisons = toSnapshotComparisons(
            coalesce(latestThemeResult.get("snapshotComparisons"), latestThemeResult.get("snapshotComparison"))
        );
        if (snapshotComparisons.isEmpty()) {
            snapshotComparisons = buildSnapshotComparisons(snapshots, ranksBySnapshot);
        }
        List<RankSnapshotVO> latestSnapshots = toLatestSnapshots(snapshots, ranksBySnapshot);
        List<ThemeTableItemVO> themeTable = toThemeTable(latestThemeResult.get("themeTable"));
        if (themeTable.isEmpty()) {
            themeTable = buildThemeTable(board, snapshotComparisons, snapshots.size());
        }
        List<ThemeWordCloudItemVO> historicalWordCloud = toWordCloud(coalesce(
            latestThemeResult.get("historicalWordCloud"),
            latestThemeResult.get("wordCloud")
        ));
        if (historicalWordCloud.isEmpty()) {
            historicalWordCloud = buildWordCloud(themeTable, board, snapshots.size());
        }
        List<HotBookVO> hotBooks = toHotBooks(latestThemeResult.get("hotBooks"));
        if (hotBooks.isEmpty()) {
            hotBooks = buildHotBooks(snapshots, ranksBySnapshot);
        }
        List<InsightCardVO> insightCards = toInsightCards(latestThemeResult.get("insightCards"));
        if (insightCards.isEmpty()) {
            insightCards = buildInsightCards(board, themeTable, hotBooks, snapshots.size());
        }
        String comparisonSummary = firstNonBlank(
            asString(latestThemeResult.get("comparisonSummary")),
            buildComparisonSummary(board, snapshotComparisons, snapshots.size())
        );

        VisualDataVO vo = new VisualDataVO();
        vo.setPlatform(platform);
        vo.setChannelCode(channelCode);
        vo.setBoardCode(boardCode);
        vo.setBoardName(board.getBoardName());
        vo.setSourceSnapshotCount(snapshots.size());
        vo.setHistoryAnalysisCount(asInteger(latestThemeResult.get("historyAnalysisCount"), snapshots.size()));
        vo.setLatestSnapshots(latestSnapshots);
        vo.setHistoricalWordCloud(historicalWordCloud);
        vo.setThemeTable(themeTable);
        vo.setHotBooks(hotBooks);
        vo.setInsightCards(insightCards);
        vo.setComparisonSummary(comparisonSummary);
        vo.setSnapshotComparisons(snapshotComparisons);
        vo.setTrendPreview(firstNonBlank(
            asString(latestThemeResult.get("trendPreview")),
            asString(latestThemeResult.get("summary")),
            comparisonSummary
        ));
        vo.setDetailContent(firstNonBlank(
            asString(latestThemeResult.get("detailContent")),
            asString(latestThemeResult.get("content"))
        ));
        return vo;
    }

    private AnalysisHistoryItemVO toHistoryItem(AnalysisResultEntity entity, CrawlBookEntity book) {
        AnalysisHistoryItemVO vo = new AnalysisHistoryItemVO();
        vo.setId(entity.getId());
        vo.setBookId(entity.getBookId());
        vo.setBookName(book == null ? null : book.getBookName());
        vo.setAnalysisType(entity.getAnalysisType());
        vo.setChapterCount(entity.getChapterCount());
        vo.setModelName(entity.getModelName());
        vo.setResultContent(entity.getResultContent());
        vo.setResultJson(readResultJson(entity.getResultJson()));
        vo.setCreatedAt(entity.getCreateTime() == null ? null : entity.getCreateTime().format(DATE_TIME_FORMATTER));
        return vo;
    }

    private List<RankSnapshotVO> toLatestSnapshots(List<RankSnapshotEntity> snapshots,
                                                   Map<Long, List<CrawlRankEntity>> ranksBySnapshot) {
        return snapshots.stream().map(snapshot -> {
            List<CrawlRankEntity> items = ranksBySnapshot.getOrDefault(snapshot.getId(), List.of());
            CrawlRankEntity topItem = items.stream()
                .min(Comparator.comparing(CrawlRankEntity::getRankNo, Comparator.nullsLast(Integer::compareTo)))
                .orElse(null);

            RankSnapshotVO vo = new RankSnapshotVO();
            vo.setSnapshotTime(snapshot.getSnapshotTime() == null
                ? null
                : snapshot.getSnapshotTime().format(DATE_TIME_FORMATTER));
            vo.setBookCount(resolveBookCount(snapshot, items));
            vo.setTopBookName(topItem == null ? null : topItem.getBookName());
            vo.setTopBookAuthor(topItem == null ? null : topItem.getAuthor());
            return vo;
        }).toList();
    }

    private Long resolveBookCount(RankSnapshotEntity snapshot, List<CrawlRankEntity> items) {
        long count = items.stream().map(CrawlRankEntity::getBookId).filter(Objects::nonNull).distinct().count();
        if (count > 0) {
            return count;
        }
        return snapshot.getRecordCount() == null ? 0L : snapshot.getRecordCount().longValue();
    }

    private List<ThemeWordCloudItemVO> toWordCloud(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                ThemeWordCloudItemVO vo = new ThemeWordCloudItemVO();
                vo.setName(asString(item.get("name")));
                vo.setValue(asLong(item.get("value")));
                return vo;
            })
            .filter(item -> item.getName() != null && item.getValue() != null)
            .toList();
    }

    private List<ThemeTableItemVO> toThemeTable(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                ThemeTableItemVO vo = new ThemeTableItemVO();
                vo.setTheme(asString(item.get("theme")));
                vo.setCount(asLong(item.get("count")));
                vo.setTrend(asString(item.get("trend")));
                return vo;
            })
            .filter(item -> item.getTheme() != null)
            .toList();
    }

    private List<ThemeTableItemVO> buildThemeTable(RankBoardEntity board,
                                                   List<SnapshotThemeComparisonVO> snapshotComparisons,
                                                   int snapshotCount) {
        if (snapshotCount <= 0) {
            return List.of();
        }

        ThemeTableItemVO vo = new ThemeTableItemVO();
        vo.setTheme(firstNonBlank(
            snapshotComparisons.isEmpty() ? null : snapshotComparisons.get(0).getTopTheme(),
            board.getBoardName()
        ));
        vo.setCount((long) Math.max(snapshotCount, 1));
        vo.setTrend(snapshotCount > 1 ? "样本积累中" : "单次快照");
        return List.of(vo);
    }

    private List<HotBookVO> toHotBooks(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                HotBookVO vo = new HotBookVO();
                vo.setBookName(asString(item.get("bookName")));
                vo.setAuthor(asString(item.get("author")));
                vo.setRankLabel(asString(item.get("rankLabel")));
                vo.setReason(asString(item.get("reason")));
                return vo;
            })
            .filter(item -> item.getBookName() != null)
            .toList();
    }

    private List<HotBookVO> buildHotBooks(List<RankSnapshotEntity> snapshots,
                                          Map<Long, List<CrawlRankEntity>> ranksBySnapshot) {
        if (snapshots.isEmpty()) {
            return List.of();
        }

        List<CrawlRankEntity> latestRanks = ranksBySnapshot.getOrDefault(snapshots.get(0).getId(), List.of());
        CrawlRankEntity topItem = latestRanks.stream()
            .min(Comparator.comparing(CrawlRankEntity::getRankNo, Comparator.nullsLast(Integer::compareTo)))
            .orElse(null);
        if (topItem == null) {
            return List.of();
        }

        HotBookVO vo = new HotBookVO();
        vo.setBookName(topItem.getBookName());
        vo.setAuthor(topItem.getAuthor());
        vo.setRankLabel("#" + (topItem.getRankNo() == null ? 0 : topItem.getRankNo()));
        vo.setReason("基于当前可用快照的榜首作品");
        return List.of(vo);
    }

    private List<InsightCardVO> toInsightCards(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                InsightCardVO vo = new InsightCardVO();
                vo.setLabel(asString(item.get("label")));
                vo.setValue(asString(item.get("value")));
                vo.setNote(asString(item.get("note")));
                return vo;
            })
            .filter(item -> item.getLabel() != null && item.getValue() != null)
            .toList();
    }

    private List<InsightCardVO> buildInsightCards(RankBoardEntity board,
                                                  List<ThemeTableItemVO> themeTable,
                                                  List<HotBookVO> hotBooks,
                                                  int snapshotCount) {
        if (snapshotCount <= 0) {
            return List.of();
        }

        InsightCardVO themeCard = new InsightCardVO();
        themeCard.setLabel("当前焦点");
        themeCard.setValue(firstNonBlank(
            themeTable.isEmpty() ? null : themeTable.get(0).getTheme(),
            board.getBoardName()
        ));
        themeCard.setNote("先基于现有 " + snapshotCount + " 次快照展示，后续会随着样本增加继续补全");

        InsightCardVO hotBookCard = new InsightCardVO();
        hotBookCard.setLabel("代表作品");
        hotBookCard.setValue(firstNonBlank(
            hotBooks.isEmpty() ? null : hotBooks.get(0).getBookName(),
            board.getBoardName()
        ));
        hotBookCard.setNote("当前按已抓到的榜首作品先展示，不再等待满三次");

        return List.of(themeCard, hotBookCard);
    }

    private List<SnapshotThemeComparisonVO> toSnapshotComparisons(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                SnapshotThemeComparisonVO vo = new SnapshotThemeComparisonVO();
                vo.setSnapshotTime(asString(item.get("snapshotTime")));
                vo.setTopTheme(asString(item.get("topTheme")));
                vo.setChange(asString(item.get("change")));
                return vo;
            })
            .filter(item -> item.getSnapshotTime() != null)
            .toList();
    }

    private List<SnapshotThemeComparisonVO> buildSnapshotComparisons(List<RankSnapshotEntity> snapshots,
                                                                     Map<Long, List<CrawlRankEntity>> ranksBySnapshot) {
        return snapshots.stream().map(snapshot -> {
            List<CrawlRankEntity> items = ranksBySnapshot.getOrDefault(snapshot.getId(), List.of());
            CrawlRankEntity topItem = items.stream()
                .min(Comparator.comparing(CrawlRankEntity::getRankNo, Comparator.nullsLast(Integer::compareTo)))
                .orElse(null);

            SnapshotThemeComparisonVO vo = new SnapshotThemeComparisonVO();
            vo.setSnapshotTime(snapshot.getSnapshotTime() == null
                ? null
                : snapshot.getSnapshotTime().format(DATE_TIME_FORMATTER));
            vo.setTopTheme(topItem == null ? null : topItem.getBookName());
            vo.setChange("snapshot");
            return vo;
        }).filter(item -> item.getSnapshotTime() != null).toList();
    }

    private List<ThemeWordCloudItemVO> buildWordCloud(List<ThemeTableItemVO> themeTable,
                                                      RankBoardEntity board,
                                                      int snapshotCount) {
        if (!themeTable.isEmpty()) {
            return themeTable.stream()
                .filter(item -> item.getTheme() != null && item.getCount() != null)
                .map(item -> {
                    ThemeWordCloudItemVO vo = new ThemeWordCloudItemVO();
                    vo.setName(item.getTheme());
                    vo.setValue(item.getCount());
                    return vo;
                })
                .toList();
        }
        if (snapshotCount <= 0) {
            return List.of();
        }

        ThemeWordCloudItemVO vo = new ThemeWordCloudItemVO();
        vo.setName(board.getBoardName());
        vo.setValue((long) snapshotCount);
        return List.of(vo);
    }

    private String buildComparisonSummary(RankBoardEntity board,
                                          List<SnapshotThemeComparisonVO> comparisons,
                                          int snapshotCount) {
        if (comparisons.isEmpty()) {
            return null;
        }
        SnapshotThemeComparisonVO latest = comparisons.get(0);
        String focus = firstNonBlank(latest.getTopTheme(), board.getBoardName());
        return "当前已展示 " + snapshotCount + " 次可用快照，最近一次聚焦在 " + focus + "。";
    }

    private int normalizeLimit(Integer limit, int defaultValue, int maxValue) {
        if (limit == null || limit <= 0) {
            return defaultValue;
        }
        return Math.min(limit, maxValue);
    }

    private Map<String, Object> readResultJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(resultJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of("raw", resultJson);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMap(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .toList();
    }

    private Object coalesce(Object first, Object second) {
        return first != null ? first : second;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer asInteger(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}

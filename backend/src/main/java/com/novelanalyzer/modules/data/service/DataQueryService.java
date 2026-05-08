package com.novelanalyzer.modules.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.utils.TrendResultJsonUtils;
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
import com.novelanalyzer.modules.data.vo.ThemeDistributionItemVO;
import com.novelanalyzer.modules.data.vo.ThemeTableItemVO;
import com.novelanalyzer.modules.data.vo.ThemeWordCloudItemVO;
import com.novelanalyzer.modules.data.vo.VisualDataVO;
import org.springframework.stereotype.Service;

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
        Map<String, Object> latestThemeResult = dataQueryRepository.findRecentBoardThemeResults(platform, channelCode, boardCode, 5).stream()
            .map(entity -> TrendResultJsonUtils.recoverThemeResultMap(
                objectMapper,
                readResultJson(entity.getResultJson()),
                entity.getResultContent()
            ))
            .filter(TrendResultJsonUtils::hasReusableThemePayload)
            .findFirst()
            .orElse(Map.of());

        List<RankSnapshotVO> latestSnapshots = toLatestSnapshots(snapshots, ranksBySnapshot);
        String summary = defaultString(TrendResultJsonUtils.extractThemeSummary(latestThemeResult));
        String boardSummary = defaultString(TrendResultJsonUtils.extractThemeBoardSummary(latestThemeResult));
        String detailContent = defaultString(TrendResultJsonUtils.extractThemeDetailContent(
            latestThemeResult,
            asString(latestThemeResult.get("content"))
        ));

        List<ThemeTableItemVO> themeTable = toThemeTable(TrendResultJsonUtils.normalizeThemeTable(latestThemeResult.get("themeTable")));
        List<ThemeDistributionItemVO> themeDistribution = toThemeDistribution(TrendResultJsonUtils.normalizeThemeDistribution(
            latestThemeResult.get("themeDistribution"),
            latestThemeResult.get("themeTable")
        ));
        List<ThemeWordCloudItemVO> historicalWordCloud = toWordCloud(TrendResultJsonUtils.normalizeThemeWordCloud(coalesce(
            latestThemeResult.get("historicalWordCloud"),
            latestThemeResult.get("wordCloud")
        )));
        List<HotBookVO> hotBooks = toHotBooks(TrendResultJsonUtils.normalizeThemeHotBooks(latestThemeResult.get("hotBooks")));
        List<InsightCardVO> insightCards = toInsightCards(latestThemeResult.get("insightCards"));
        List<SnapshotThemeComparisonVO> snapshotComparisons = toSnapshotComparisons(
            coalesce(latestThemeResult.get("snapshotComparisons"), latestThemeResult.get("snapshotComparison"))
        );

        VisualDataVO vo = new VisualDataVO();
        vo.setPlatform(platform);
        vo.setChannelCode(channelCode);
        vo.setBoardCode(boardCode);
        vo.setBoardName(board.getBoardName());
        vo.setSourceSnapshotCount(snapshots.size());
        vo.setHistoryAnalysisCount(asInteger(latestThemeResult.get("historyAnalysisCount"), snapshots.size()));
        vo.setLatestSnapshots(latestSnapshots);
        vo.setBoardSummary(boardSummary);
        vo.setHistoricalWordCloud(historicalWordCloud);
        vo.setThemeDistribution(themeDistribution);
        vo.setThemeTable(themeTable);
        vo.setHotBooks(hotBooks);
        vo.setInsightCards(insightCards);
        vo.setComparisonSummary(defaultString(asString(latestThemeResult.get("comparisonSummary"))));
        vo.setSnapshotComparisons(snapshotComparisons);
        vo.setTrendPreview(defaultString(TrendResultJsonUtils.extractThemeTrendPreview(latestThemeResult)));
        vo.setDetailContent(detailContent);
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

    private List<ThemeDistributionItemVO> toThemeDistribution(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                ThemeDistributionItemVO vo = new ThemeDistributionItemVO();
                vo.setTheme(asString(item.get("theme")));
                vo.setCount(asLong(item.get("count")));
                vo.setRatio(asDouble(item.get("ratio")));
                return vo;
            })
            .filter(item -> item.getTheme() != null)
            .toList();
    }

    private List<ThemeTableItemVO> toThemeTable(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                ThemeTableItemVO vo = new ThemeTableItemVO();
                vo.setTheme(asString(item.get("theme")));
                vo.setCount(asLong(item.get("count")));
                vo.setRatio(asDouble(item.get("ratio")));
                vo.setTrend(asString(item.get("trend")));
                vo.setRepresentativeBooks(toHotBooks(item.get("representativeBooks")));
                return vo;
            })
            .filter(item -> item.getTheme() != null)
            .toList();
    }

    private List<HotBookVO> toHotBooks(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                HotBookVO vo = new HotBookVO();
                Integer rankNo = asIntegerOrNull(item.get("rankNo"));
                vo.setTheme(asString(item.get("theme")));
                vo.setBookName(asString(item.get("bookName")));
                vo.setAuthor(asString(item.get("author")));
                vo.setRankNo(rankNo);
                vo.setRankLabel(normalizeRankLabel(rankNo, asString(item.get("rankLabel"))));
                vo.setReason(asString(item.get("reason")));
                return vo;
            })
            .filter(item -> item.getBookName() != null)
            .toList();
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

    private List<SnapshotThemeComparisonVO> toSnapshotComparisons(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                SnapshotThemeComparisonVO vo = new SnapshotThemeComparisonVO();
                vo.setSnapshotTime(asString(item.get("snapshotTime")));
                vo.setTopTheme(asString(item.get("topTheme")));
                vo.setTopThemeRatio(asDouble(item.get("topThemeRatio")));
                vo.setLeadBookName(asString(item.get("leadBookName")));
                vo.setChange(asString(item.get("change")));
                return vo;
            })
            .filter(item -> item.getSnapshotTime() != null)
            .toList();
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

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
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

    private Integer asIntegerOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeRankLabel(Integer rankNo, String rankLabel) {
        if (rankLabel != null && !rankLabel.isBlank()) {
            return rankLabel;
        }
        if (rankNo == null || rankNo <= 0) {
            return null;
        }
        return "#" + rankNo;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String shortText(String value, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit).trim() + "...";
    }
}

package com.novelanalyzer.modules.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlRankEntity;
import com.novelanalyzer.modules.data.repository.DataQueryRepository;
import com.novelanalyzer.modules.data.vo.AnalysisHistoryItemVO;
import com.novelanalyzer.modules.data.vo.ChartItemVO;
import com.novelanalyzer.modules.data.vo.DailyCountVO;
import com.novelanalyzer.modules.data.vo.RankSnapshotVO;
import com.novelanalyzer.modules.data.vo.SnapshotThemeComparisonVO;
import com.novelanalyzer.modules.data.vo.ThemeTableItemVO;
import com.novelanalyzer.modules.data.vo.ThemeWordCloudItemVO;
import com.novelanalyzer.modules.data.vo.VisualDataVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DataQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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

    public VisualDataVO getVisualData(String platform) {
        List<AnalysisResultEntity> analysisResults = dataQueryRepository.findAnalysisResultsByPlatform(platform);
        List<CrawlRankEntity> rankItems = dataQueryRepository.findRanksByPlatform(platform);
        Map<String, Object> latestThemeResult = dataQueryRepository.findLatestAnalysisResult(platform, "theme")
            .map(AnalysisResultEntity::getResultJson)
            .map(this::readResultJson)
            .orElse(Map.of());

        VisualDataVO vo = new VisualDataVO();
        vo.setAnalysisTypeDistribution(toAnalysisTypeDistribution(analysisResults));
        vo.setAnalysisDailyTrend(toAnalysisDailyTrend(analysisResults));
        vo.setRankCategoryDistribution(toRankCategoryDistribution(rankItems));
        vo.setLatestSnapshots(toLatestSnapshots(rankItems, 3));
        vo.setWordCloud(toWordCloud(latestThemeResult.get("wordCloud")));
        vo.setThemeTable(toThemeTable(latestThemeResult.get("themeTable")));
        vo.setComparisonSummary(asString(latestThemeResult.get("comparisonSummary")));
        vo.setSnapshotComparisons(toSnapshotComparisons(latestThemeResult.get("snapshotComparison")));
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

    private List<ChartItemVO> toAnalysisTypeDistribution(List<AnalysisResultEntity> results) {
        return results.stream()
            .collect(Collectors.groupingBy(AnalysisResultEntity::getAnalysisType, Collectors.counting()))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new ChartItemVO(entry.getKey(), entry.getValue()))
            .toList();
    }

    private List<DailyCountVO> toAnalysisDailyTrend(List<AnalysisResultEntity> results) {
        return results.stream()
            .filter(item -> item.getCreateTime() != null)
            .collect(Collectors.groupingBy(item -> item.getCreateTime().toLocalDate(), Collectors.counting()))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new DailyCountVO(entry.getKey().format(DATE_FORMATTER), entry.getValue()))
            .toList();
    }

    private List<ChartItemVO> toRankCategoryDistribution(List<CrawlRankEntity> rankItems) {
        return rankItems.stream()
            .collect(Collectors.groupingBy(CrawlRankEntity::getCategory, Collectors.counting()))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new ChartItemVO(entry.getKey(), entry.getValue()))
            .toList();
    }

    private List<RankSnapshotVO> toLatestSnapshots(List<CrawlRankEntity> rankItems, int snapshotCount) {
        Map<LocalDateTime, List<CrawlRankEntity>> snapshots = new LinkedHashMap<>();
        for (CrawlRankEntity item : rankItems.stream()
            .sorted(Comparator.comparing(CrawlRankEntity::getCrawlTime, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList()) {
            LocalDateTime crawlTime = item.getCrawlTime();
            if (crawlTime == null) {
                continue;
            }
            if (!snapshots.containsKey(crawlTime) && snapshots.size() >= snapshotCount) {
                continue;
            }
            snapshots.computeIfAbsent(crawlTime, key -> new ArrayList<>()).add(item);
        }

        return snapshots.entrySet().stream()
            .map(entry -> {
                RankSnapshotVO vo = new RankSnapshotVO();
                vo.setCrawlTime(entry.getKey().format(DATE_TIME_FORMATTER));
                vo.setCategory(resolveSnapshotCategory(entry.getValue()));
                vo.setBookCount(entry.getValue().stream().map(CrawlRankEntity::getBookId).distinct().count());
                return vo;
            })
            .toList();
    }

    private String resolveSnapshotCategory(List<CrawlRankEntity> snapshotItems) {
        return snapshotItems.stream()
            .map(CrawlRankEntity::getCategory)
            .filter(Objects::nonNull)
            .distinct()
            .reduce((left, right) -> "mixed")
            .orElse("unknown");
    }

    private List<ThemeWordCloudItemVO> toWordCloud(Object rawValue) {
        return asListOfMap(rawValue).stream()
            .map(item -> {
                ThemeWordCloudItemVO vo = new ThemeWordCloudItemVO();
                vo.setName(asString(item.get("name")));
                vo.setValue(asLong(item.get("value")));
                return vo;
            })
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
            .toList();
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
}

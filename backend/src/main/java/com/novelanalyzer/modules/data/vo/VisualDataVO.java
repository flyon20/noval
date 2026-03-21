package com.novelanalyzer.modules.data.vo;

import java.util.List;

public class VisualDataVO {

    private List<ChartItemVO> analysisTypeDistribution;
    private List<DailyCountVO> analysisDailyTrend;
    private List<ChartItemVO> rankCategoryDistribution;
    private List<RankSnapshotVO> latestSnapshots;
    private List<ThemeWordCloudItemVO> wordCloud;
    private List<ThemeTableItemVO> themeTable;
    private String comparisonSummary;
    private List<SnapshotThemeComparisonVO> snapshotComparisons;

    public List<ChartItemVO> getAnalysisTypeDistribution() {
        return analysisTypeDistribution;
    }

    public void setAnalysisTypeDistribution(List<ChartItemVO> analysisTypeDistribution) {
        this.analysisTypeDistribution = analysisTypeDistribution;
    }

    public List<DailyCountVO> getAnalysisDailyTrend() {
        return analysisDailyTrend;
    }

    public void setAnalysisDailyTrend(List<DailyCountVO> analysisDailyTrend) {
        this.analysisDailyTrend = analysisDailyTrend;
    }

    public List<ChartItemVO> getRankCategoryDistribution() {
        return rankCategoryDistribution;
    }

    public void setRankCategoryDistribution(List<ChartItemVO> rankCategoryDistribution) {
        this.rankCategoryDistribution = rankCategoryDistribution;
    }

    public List<RankSnapshotVO> getLatestSnapshots() {
        return latestSnapshots;
    }

    public void setLatestSnapshots(List<RankSnapshotVO> latestSnapshots) {
        this.latestSnapshots = latestSnapshots;
    }

    public List<ThemeWordCloudItemVO> getWordCloud() {
        return wordCloud;
    }

    public void setWordCloud(List<ThemeWordCloudItemVO> wordCloud) {
        this.wordCloud = wordCloud;
    }

    public List<ThemeTableItemVO> getThemeTable() {
        return themeTable;
    }

    public void setThemeTable(List<ThemeTableItemVO> themeTable) {
        this.themeTable = themeTable;
    }

    public String getComparisonSummary() {
        return comparisonSummary;
    }

    public void setComparisonSummary(String comparisonSummary) {
        this.comparisonSummary = comparisonSummary;
    }

    public List<SnapshotThemeComparisonVO> getSnapshotComparisons() {
        return snapshotComparisons;
    }

    public void setSnapshotComparisons(List<SnapshotThemeComparisonVO> snapshotComparisons) {
        this.snapshotComparisons = snapshotComparisons;
    }
}

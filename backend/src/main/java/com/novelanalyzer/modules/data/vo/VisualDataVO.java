package com.novelanalyzer.modules.data.vo;

import java.util.List;

public class VisualDataVO {

    private String platform;
    private String channelCode;
    private String boardCode;
    private String boardName;
    private Integer sourceSnapshotCount;
    private Integer historyAnalysisCount;
    private List<RankSnapshotVO> latestSnapshots;
    private List<ThemeWordCloudItemVO> historicalWordCloud;
    private List<ThemeTableItemVO> themeTable;
    private List<HotBookVO> hotBooks;
    private List<InsightCardVO> insightCards;
    private String comparisonSummary;
    private List<SnapshotThemeComparisonVO> snapshotComparisons;
    private String trendPreview;
    private String detailContent;

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getBoardCode() {
        return boardCode;
    }

    public void setBoardCode(String boardCode) {
        this.boardCode = boardCode;
    }

    public String getBoardName() {
        return boardName;
    }

    public void setBoardName(String boardName) {
        this.boardName = boardName;
    }

    public Integer getSourceSnapshotCount() {
        return sourceSnapshotCount;
    }

    public void setSourceSnapshotCount(Integer sourceSnapshotCount) {
        this.sourceSnapshotCount = sourceSnapshotCount;
    }

    public Integer getHistoryAnalysisCount() {
        return historyAnalysisCount;
    }

    public void setHistoryAnalysisCount(Integer historyAnalysisCount) {
        this.historyAnalysisCount = historyAnalysisCount;
    }

    public List<RankSnapshotVO> getLatestSnapshots() {
        return latestSnapshots;
    }

    public void setLatestSnapshots(List<RankSnapshotVO> latestSnapshots) {
        this.latestSnapshots = latestSnapshots;
    }

    public List<ThemeWordCloudItemVO> getHistoricalWordCloud() {
        return historicalWordCloud;
    }

    public void setHistoricalWordCloud(List<ThemeWordCloudItemVO> historicalWordCloud) {
        this.historicalWordCloud = historicalWordCloud;
    }

    public List<ThemeTableItemVO> getThemeTable() {
        return themeTable;
    }

    public void setThemeTable(List<ThemeTableItemVO> themeTable) {
        this.themeTable = themeTable;
    }

    public List<HotBookVO> getHotBooks() {
        return hotBooks;
    }

    public void setHotBooks(List<HotBookVO> hotBooks) {
        this.hotBooks = hotBooks;
    }

    public List<InsightCardVO> getInsightCards() {
        return insightCards;
    }

    public void setInsightCards(List<InsightCardVO> insightCards) {
        this.insightCards = insightCards;
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

    public String getTrendPreview() {
        return trendPreview;
    }

    public void setTrendPreview(String trendPreview) {
        this.trendPreview = trendPreview;
    }

    public String getDetailContent() {
        return detailContent;
    }

    public void setDetailContent(String detailContent) {
        this.detailContent = detailContent;
    }
}

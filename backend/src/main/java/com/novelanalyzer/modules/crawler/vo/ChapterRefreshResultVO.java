package com.novelanalyzer.modules.crawler.vo;

import java.util.List;

public class ChapterRefreshResultVO {

    private List<ChapterVO> chapters;
    private Integer maxAllowedRefreshTimes;
    private Integer usedRefreshTimes;
    private Integer remainingRefreshTimes;
    private Integer windowDays;

    public List<ChapterVO> getChapters() {
        return chapters;
    }

    public void setChapters(List<ChapterVO> chapters) {
        this.chapters = chapters;
    }

    public Integer getMaxAllowedRefreshTimes() {
        return maxAllowedRefreshTimes;
    }

    public void setMaxAllowedRefreshTimes(Integer maxAllowedRefreshTimes) {
        this.maxAllowedRefreshTimes = maxAllowedRefreshTimes;
    }

    public Integer getUsedRefreshTimes() {
        return usedRefreshTimes;
    }

    public void setUsedRefreshTimes(Integer usedRefreshTimes) {
        this.usedRefreshTimes = usedRefreshTimes;
    }

    public Integer getRemainingRefreshTimes() {
        return remainingRefreshTimes;
    }

    public void setRemainingRefreshTimes(Integer remainingRefreshTimes) {
        this.remainingRefreshTimes = remainingRefreshTimes;
    }

    public Integer getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(Integer windowDays) {
        this.windowDays = windowDays;
    }
}

package com.novelanalyzer.modules.data.vo;

import java.util.List;

public class ThemeTableItemVO {

    private String theme;
    private Long count;
    private Double ratio;
    private String trend;
    private List<HotBookVO> representativeBooks;

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }

    public Double getRatio() {
        return ratio;
    }

    public void setRatio(Double ratio) {
        this.ratio = ratio;
    }

    public String getTrend() {
        return trend;
    }

    public void setTrend(String trend) {
        this.trend = trend;
    }

    public List<HotBookVO> getRepresentativeBooks() {
        return representativeBooks;
    }

    public void setRepresentativeBooks(List<HotBookVO> representativeBooks) {
        this.representativeBooks = representativeBooks;
    }
}

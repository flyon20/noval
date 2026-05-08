package com.novelanalyzer.modules.data.vo;

public class SnapshotThemeComparisonVO {

    private String snapshotTime;
    private String topTheme;
    private Double topThemeRatio;
    private String leadBookName;
    private String change;

    public String getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(String snapshotTime) {
        this.snapshotTime = snapshotTime;
    }

    public String getTopTheme() {
        return topTheme;
    }

    public void setTopTheme(String topTheme) {
        this.topTheme = topTheme;
    }

    public Double getTopThemeRatio() {
        return topThemeRatio;
    }

    public void setTopThemeRatio(Double topThemeRatio) {
        this.topThemeRatio = topThemeRatio;
    }

    public String getLeadBookName() {
        return leadBookName;
    }

    public void setLeadBookName(String leadBookName) {
        this.leadBookName = leadBookName;
    }

    public String getChange() {
        return change;
    }

    public void setChange(String change) {
        this.change = change;
    }
}

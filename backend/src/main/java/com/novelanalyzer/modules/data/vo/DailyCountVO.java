package com.novelanalyzer.modules.data.vo;

public class DailyCountVO {

    private String date;
    private Long value;

    public DailyCountVO() {
    }

    public DailyCountVO(String date, Long value) {
        this.date = date;
        this.value = value;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Long getValue() {
        return value;
    }

    public void setValue(Long value) {
        this.value = value;
    }
}

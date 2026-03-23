package com.novelanalyzer.modules.data.vo;

public class RankSnapshotVO {

    private String snapshotTime;
    private Long bookCount;
    private String topBookName;
    private String topBookAuthor;

    public String getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(String snapshotTime) {
        this.snapshotTime = snapshotTime;
    }

    public Long getBookCount() {
        return bookCount;
    }

    public void setBookCount(Long bookCount) {
        this.bookCount = bookCount;
    }

    public String getTopBookName() {
        return topBookName;
    }

    public void setTopBookName(String topBookName) {
        this.topBookName = topBookName;
    }

    public String getTopBookAuthor() {
        return topBookAuthor;
    }

    public void setTopBookAuthor(String topBookAuthor) {
        this.topBookAuthor = topBookAuthor;
    }
}

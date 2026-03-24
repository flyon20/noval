package com.novelanalyzer.modules.crawler.client.model;

public class ExternalChapterItem {

    private Integer chapterNo;
    private String chapterTitle;
    private String content;
    private Integer sourceWordCount;

    public Integer getChapterNo() {
        return chapterNo;
    }

    public void setChapterNo(Integer chapterNo) {
        this.chapterNo = chapterNo;
    }

    public String getChapterTitle() {
        return chapterTitle;
    }

    public void setChapterTitle(String chapterTitle) {
        this.chapterTitle = chapterTitle;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getSourceWordCount() {
        return sourceWordCount;
    }

    public void setSourceWordCount(Integer sourceWordCount) {
        this.sourceWordCount = sourceWordCount;
    }
}

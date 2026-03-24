package com.novelanalyzer.modules.crawler.vo;

public class ChapterVO {

    private Long bookId;
    private Integer chapterNo;
    private String chapterTitle;
    private String content;
    private Integer wordCount;
    private Integer sourceWordCount;

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

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

    public Integer getWordCount() {
        return wordCount;
    }

    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    public Integer getSourceWordCount() {
        return sourceWordCount;
    }

    public void setSourceWordCount(Integer sourceWordCount) {
        this.sourceWordCount = sourceWordCount;
    }
}

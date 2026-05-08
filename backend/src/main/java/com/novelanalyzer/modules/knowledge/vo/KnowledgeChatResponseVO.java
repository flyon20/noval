package com.novelanalyzer.modules.knowledge.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeChatResponseVO {

    private String status;
    private String answer;
    private List<CandidateVO> candidates = new ArrayList<>();
    private List<SourceVO> sources = new ArrayList<>();
    private List<String> actions = new ArrayList<>();
    private Map<String, Object> resultJson = new LinkedHashMap<>();

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<CandidateVO> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<CandidateVO> candidates) {
        this.candidates = candidates == null ? new ArrayList<>() : candidates;
    }

    public List<SourceVO> getSources() {
        return sources;
    }

    public void setSources(List<SourceVO> sources) {
        this.sources = sources == null ? new ArrayList<>() : sources;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions == null ? new ArrayList<>() : actions;
    }

    public Map<String, Object> getResultJson() {
        return resultJson;
    }

    public void setResultJson(Map<String, Object> resultJson) {
        this.resultJson = resultJson == null ? new LinkedHashMap<>() : resultJson;
    }

    public static class CandidateVO {
        private Long bookId;
        private String platform;
        private String platformBookId;
        private String bookName;
        private String author;
        private String intro;
        private String bookUrl;
        private Boolean local;

        public Long getBookId() {
            return bookId;
        }

        public void setBookId(Long bookId) {
            this.bookId = bookId;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public String getPlatformBookId() {
            return platformBookId;
        }

        public void setPlatformBookId(String platformBookId) {
            this.platformBookId = platformBookId;
        }

        public String getBookName() {
            return bookName;
        }

        public void setBookName(String bookName) {
            this.bookName = bookName;
        }

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public String getIntro() {
            return intro;
        }

        public void setIntro(String intro) {
            this.intro = intro;
        }

        public String getBookUrl() {
            return bookUrl;
        }

        public void setBookUrl(String bookUrl) {
            this.bookUrl = bookUrl;
        }

        public Boolean getLocal() {
            return local;
        }

        public void setLocal(Boolean local) {
            this.local = local;
        }
    }

    public static class SourceVO {
        private Long chunkId;
        private Long documentId;
        private Double score;
        private Long bookId;
        private String bookName;
        private String platform;
        private String sourceType;
        private Long sourceRefId;
        private Integer chapterNo;
        private String analysisType;
        private String title;
        private String preview;

        public Long getChunkId() {
            return chunkId;
        }

        public void setChunkId(Long chunkId) {
            this.chunkId = chunkId;
        }

        public Long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(Long documentId) {
            this.documentId = documentId;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }

        public Long getBookId() {
            return bookId;
        }

        public void setBookId(Long bookId) {
            this.bookId = bookId;
        }

        public String getBookName() {
            return bookName;
        }

        public void setBookName(String bookName) {
            this.bookName = bookName;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public String getSourceType() {
            return sourceType;
        }

        public void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }

        public Long getSourceRefId() {
            return sourceRefId;
        }

        public void setSourceRefId(Long sourceRefId) {
            this.sourceRefId = sourceRefId;
        }

        public Integer getChapterNo() {
            return chapterNo;
        }

        public void setChapterNo(Integer chapterNo) {
            this.chapterNo = chapterNo;
        }

        public String getAnalysisType() {
            return analysisType;
        }

        public void setAnalysisType(String analysisType) {
            this.analysisType = analysisType;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getPreview() {
            return preview;
        }

        public void setPreview(String preview) {
            this.preview = preview;
        }
    }
}

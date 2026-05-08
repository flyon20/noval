package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeChatRequest {

    @NotBlank(message = "question is required")
    private String question;
    private String bookName;
    private Long bookId;
    @Valid
    private CandidateDTO selectedCandidate;
    private String mode;
    private String contextSummary;
    @Valid
    private List<ChatMessageDTO> history = List.of();
    private Map<String, Object> limits = new LinkedHashMap<>();

    @AssertTrue(message = "question is required")
    public boolean isQuestionValid() {
        return question != null && !question.trim().isEmpty();
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public CandidateDTO getSelectedCandidate() {
        return selectedCandidate;
    }

    public void setSelectedCandidate(CandidateDTO selectedCandidate) {
        this.selectedCandidate = selectedCandidate;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getContextSummary() {
        return contextSummary;
    }

    public void setContextSummary(String contextSummary) {
        this.contextSummary = contextSummary;
    }

    public List<ChatMessageDTO> getHistory() {
        return history;
    }

    public void setHistory(List<ChatMessageDTO> history) {
        this.history = history == null ? List.of() : List.copyOf(history);
    }

    public Map<String, Object> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Object> limits) {
        this.limits = limits == null ? new LinkedHashMap<>() : new LinkedHashMap<>(limits);
    }

    public static class CandidateDTO {
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

    public static class ChatMessageDTO {
        private String role;
        private String content;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}

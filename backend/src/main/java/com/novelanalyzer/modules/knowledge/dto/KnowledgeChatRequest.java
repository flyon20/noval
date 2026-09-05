package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeChatRequest {

    @NotBlank(message = "question is required")
    private String question;
    private String bookName;
    private Long bookId;
    private Long projectId;
    private Long workId;
    @Size(max = 8, message = "referenceWorkIds exceeds maximum size")
    private List<@Positive(message = "reference work id must be positive") Long> referenceWorkIds = List.of();
    private String conversationId;
    private String requestId;
    @Valid
    private CandidateDTO selectedCandidate;
    private String mode;
    private String reasoningMode;
    @Size(max = 20, message = "reasoningEffort is too long")
    private String reasoningEffort;
    @Size(max = 200, message = "modelKey is too long")
    private String modelKey;
    @Size(max = 120, message = "preferredSkillId is too long")
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]*$", message = "preferredSkillId is invalid")
    private String preferredSkillId;
    private Boolean resumeFromCheckpoint;
    private String contextSummary;
    @Valid
    private List<ChatMessageDTO> history = List.of();
    private Map<String, Object> limits = new LinkedHashMap<>();

    @AssertTrue(message = "question is required")
    public boolean isQuestionValid() {
        return question != null && !question.trim().isEmpty();
    }

    @AssertTrue(message = "projectId is required when workId is provided")
    public boolean isWorkScopeValid() {
        return workId == null || projectId != null;
    }

    @AssertTrue(message = "projectId and workId are required when referenceWorkIds are provided")
    public boolean isReferenceWorkScopeValid() {
        return referenceWorkIds == null || referenceWorkIds.isEmpty() || (projectId != null && workId != null);
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

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getWorkId() {
        return workId;
    }

    public void setWorkId(Long workId) {
        this.workId = workId;
    }

    public List<Long> getReferenceWorkIds() {
        return referenceWorkIds;
    }

    public void setReferenceWorkIds(List<Long> referenceWorkIds) {
        this.referenceWorkIds = referenceWorkIds == null ? List.of() : List.copyOf(referenceWorkIds);
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public String getReasoningMode() {
        return reasoningMode;
    }

    public void setReasoningMode(String reasoningMode) {
        this.reasoningMode = reasoningMode;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public String getPreferredSkillId() {
        return preferredSkillId;
    }

    public void setPreferredSkillId(String preferredSkillId) {
        this.preferredSkillId = preferredSkillId;
    }

    public Boolean getResumeFromCheckpoint() {
        return resumeFromCheckpoint;
    }

    public void setResumeFromCheckpoint(Boolean resumeFromCheckpoint) {
        this.resumeFromCheckpoint = resumeFromCheckpoint;
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
        private String contentType;
        private Boolean readableNovel;
        private String unavailableReason;

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

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public Boolean getReadableNovel() {
            return readableNovel;
        }

        public void setReadableNovel(Boolean readableNovel) {
            this.readableNovel = readableNovel;
        }

        public String getUnavailableReason() {
            return unavailableReason;
        }

        public void setUnavailableReason(String unavailableReason) {
            this.unavailableReason = unavailableReason;
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

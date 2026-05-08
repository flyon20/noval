package com.novelanalyzer.modules.knowledge.dto;

import jakarta.validation.constraints.NotNull;

public class KnowledgeIndexRequest {

    @NotNull(message = "bookId is required")
    private Long bookId;

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }
}

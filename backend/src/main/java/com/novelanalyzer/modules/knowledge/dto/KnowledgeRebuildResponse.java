package com.novelanalyzer.modules.knowledge.dto;

import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;

import java.util.List;

public record KnowledgeRebuildResponse(String mode, int submittedCount, List<AsyncJobSubmitResponse> jobs) {
}

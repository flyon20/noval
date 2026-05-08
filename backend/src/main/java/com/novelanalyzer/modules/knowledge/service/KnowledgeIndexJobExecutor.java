package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexJobExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexJobExecutor.class);

    private final KnowledgeIndexService knowledgeIndexService;
    private final AsyncJobService asyncJobService;
    private final TaskExecutor knowledgeIndexTaskExecutor;

    public KnowledgeIndexJobExecutor(KnowledgeIndexService knowledgeIndexService,
                                     AsyncJobService asyncJobService,
                                     @Qualifier("knowledgeIndexTaskExecutor") TaskExecutor knowledgeIndexTaskExecutor) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.asyncJobService = asyncJobService;
        this.knowledgeIndexTaskExecutor = knowledgeIndexTaskExecutor;
    }

    public AsyncJobSubmitResponse submitAndExecute(Long bookId, Long triggerUserId) {
        AsyncJobSubmitResponse response = knowledgeIndexService.submitBookIndexJob(bookId, triggerUserId);
        if (response == null || Boolean.TRUE.equals(response.getReused())) {
            return response;
        }
        knowledgeIndexTaskExecutor.execute(() -> executeSubmittedJob(bookId, response, false));
        return response;
    }

    public AsyncJobSubmitResponse submitAndExecuteBlocking(Long bookId, Long triggerUserId) {
        AsyncJobSubmitResponse response = knowledgeIndexService.submitBookIndexJob(bookId, triggerUserId);
        if (response == null || Boolean.TRUE.equals(response.getReused())) {
            return response;
        }
        executeSubmittedJob(bookId, response, true);
        return response;
    }

    private void executeSubmittedJob(Long bookId, AsyncJobSubmitResponse response, boolean rethrow) {
        try {
            KnowledgeIndexService.IndexResult result = knowledgeIndexService.indexBook(bookId);
            asyncJobService.markSuccess(
                response.getJobId(),
                "knowledge_book",
                bookId,
                "indexedChunks=" + result.indexedChunks() + ", createdChunks=" + result.createdChunks()
            );
        } catch (RuntimeException ex) {
            LOGGER.warn("knowledge index job failed: jobId={}, bookId={}, message={}",
                response.getJobId(),
                bookId,
                ex.getMessage());
            asyncJobService.markFailed(response.getJobId(), ex.getMessage());
            if (rethrow) {
                throw ex;
            }
        } finally {
            asyncJobService.releaseLock(response);
        }
    }
}

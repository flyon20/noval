package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexJobExecutorTest {

    @Test
    void shouldExecuteNewKnowledgeIndexBookJobAndMarkSuccess() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = new KnowledgeIndexJobExecutor(indexService, asyncJobService, new SyncTaskExecutor());
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(10L);
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexJob(101L, 7L)).thenReturn(response);
        when(indexService.indexBook(101L)).thenReturn(new KnowledgeIndexService.IndexResult(101L, 3, 3));

        executor.submitAndExecute(101L, 7L);

        verify(indexService).indexBook(101L);
        verify(asyncJobService).markSuccess(10L, "knowledge_book", 101L, "indexedChunks=3, createdChunks=3");
        verify(asyncJobService).releaseLock(response);
    }

    @Test
    void shouldSkipExecutionWhenExistingJobIsReused() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = new KnowledgeIndexJobExecutor(indexService, asyncJobService, new SyncTaskExecutor());
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(11L);
        response.setAcquired(false);
        response.setReused(true);
        when(indexService.submitBookIndexJob(101L, 7L)).thenReturn(response);

        executor.submitAndExecute(101L, 7L);

        verify(indexService, never()).indexBook(101L);
        verify(asyncJobService, never()).markSuccess(anyLong(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldMarkJobFailedWhenIndexingThrows() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = new KnowledgeIndexJobExecutor(indexService, asyncJobService, new SyncTaskExecutor());
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(12L);
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexJob(101L, 7L)).thenReturn(response);
        when(indexService.indexBook(101L)).thenThrow(new IllegalStateException("qdrant down"));

        executor.submitAndExecute(101L, 7L);

        verify(asyncJobService).markFailed(12L, "qdrant down");
        verify(asyncJobService).releaseLock(response);
    }

    @Test
    void shouldExecuteBlockingKnowledgeIndexBookJobBeforeReturning() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = new KnowledgeIndexJobExecutor(indexService, asyncJobService, new SyncTaskExecutor());
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(13L);
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexJob(202L, 7L)).thenReturn(response);
        when(indexService.indexBook(202L)).thenReturn(new KnowledgeIndexService.IndexResult(202L, 4, 4));

        executor.submitAndExecuteBlocking(202L, 7L);

        verify(indexService).indexBook(202L);
        verify(asyncJobService).markSuccess(13L, "knowledge_book", 202L, "indexedChunks=4, createdChunks=4");
        verify(asyncJobService).releaseLock(response);
    }
}

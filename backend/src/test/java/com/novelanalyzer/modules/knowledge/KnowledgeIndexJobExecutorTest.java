package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import com.novelanalyzer.modules.asyncjob.vo.AsyncJobVO;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexService;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeIndexJobExecutorTest {

    @Test
    void shouldRejectDirectIndexingAndDelayQueuedIndexingUnderResourcePressure() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        AgentResourcePressureService pressureService = mock(AgentResourcePressureService.class);
        when(pressureService.shouldPauseIndexing()).thenReturn(true);
        KnowledgeProperties directProperties = new KnowledgeProperties();
        directProperties.getIndex().setQueueEnabled(false);
        KnowledgeIndexJobExecutor directExecutor = new KnowledgeIndexJobExecutor(
            indexService,
            asyncJobService,
            new SyncTaskExecutor(),
            mock(KnowledgeRepository.class),
            queueService,
            directProperties,
            pressureService
        );

        assertThatThrownBy(() -> directExecutor.submitAndExecute(101L, 7L))
            .isInstanceOf(com.novelanalyzer.common.exception.BusinessException.class);
        verify(indexService, never()).submitBookIndexJob(anyLong(), anyLong(), anyString());

        KnowledgeProperties queuedProperties = new KnowledgeProperties();
        queuedProperties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor queuedExecutor = new KnowledgeIndexJobExecutor(
            indexService,
            asyncJobService,
            new SyncTaskExecutor(),
            mock(KnowledgeRepository.class),
            queueService,
            queuedProperties,
            pressureService
        );
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
            10L, "KNOWLEDGE_INDEX_BOOK", "job-key", "book:101", "{}", 101L, 7L,
            "ALL", "lock", "value", 0, null
        );
        when(queueService.retry(any(), eq(0), anyLong())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThat(queuedExecutor.handleQueuedJob(item))
            .isEqualTo(KnowledgeIndexJobExecutor.QueueConsumeAction.ACK);
        verify(indexService, never()).indexBook(anyLong(), anyString(), any(KnowledgeIndexService.IndexExecutionGuard.class));
    }

    @Test
    void shouldExecuteNewKnowledgeIndexBookJobAndMarkSuccess() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(10L);
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexJob(101L, 7L, "ALL")).thenReturn(response);
        when(indexService.indexBook(eq(101L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class)))
            .thenReturn(new KnowledgeIndexService.IndexResult(101L, 3, 3));

        executor.submitAndExecute(101L, 7L);

        verify(indexService).indexBook(eq(101L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(asyncJobService).markSuccess(10L, "knowledge_book", 101L, "indexedChunks=3, createdChunks=3", 0);
        verify(asyncJobService).releaseLock(response);
    }

    @Test
    void shouldSkipExecutionWhenExistingJobIsReused() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(11L);
        response.setAcquired(false);
        response.setReused(true);
        when(indexService.submitBookIndexJob(101L, 7L, "ALL")).thenReturn(response);

        executor.submitAndExecute(101L, 7L);

        verify(indexService, never()).indexBook(eq(101L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(asyncJobService, never()).markSuccess(anyLong(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldForwardDurableActionKeyAndReuseSuccessfulIndexJob() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(111L);
        response.setStatus(AsyncJobService.STATUS_SUCCESS);
        response.setAcquired(false);
        response.setReused(true);
        when(indexService.submitBookIndexJob(
            101L,
            7L,
            "ALL",
            "chat-run:run-1:outbox:9:index-book"
        )).thenReturn(response);

        AsyncJobSubmitResponse result = executor.submitAndExecute(
            101L,
            7L,
            "ALL",
            "chat-run:run-1:outbox:9:index-book"
        );

        org.assertj.core.api.Assertions.assertThat(result.getJobId()).isEqualTo(111L);
        org.assertj.core.api.Assertions.assertThat(result.getStatus()).isEqualTo(AsyncJobService.STATUS_SUCCESS);
        verify(indexService, never()).indexBook(eq(101L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(asyncJobService, never()).markSuccess(anyLong(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldMarkJobFailedWhenIndexingThrows() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(12L);
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexJob(101L, 7L, "ALL")).thenReturn(response);
        when(indexService.indexBook(eq(101L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class)))
            .thenThrow(new IllegalStateException("qdrant down"));

        executor.submitAndExecute(101L, 7L);

        verify(asyncJobService).markFailed(12L, "qdrant down", 0);
        verify(asyncJobService).releaseLock(response);
    }

    @Test
    void shouldExecuteBlockingKnowledgeIndexBookJobBeforeReturning() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(13L);
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexJob(202L, 7L)).thenReturn(response);
        when(indexService.indexBook(eq(202L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class)))
            .thenReturn(new KnowledgeIndexService.IndexResult(202L, 4, 4));

        executor.submitAndExecuteBlocking(202L, 7L);

        verify(indexService).indexBook(eq(202L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(asyncJobService).markSuccess(13L, "knowledge_book", 202L, "indexedChunks=4, createdChunks=4", 0);
        verify(asyncJobService).releaseLock(response);
    }

    @Test
    void shouldNotCommitDirectIndexResultWhenGenerationFenceIsLost() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(131L);
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexJob(203L, 7L, "ALL")).thenReturn(response);
        when(indexService.indexBook(eq(203L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class)))
            .thenReturn(new KnowledgeIndexService.IndexResult(203L, 4, 4));
        when(asyncJobService.markSuccess(anyLong(), anyString(), anyLong(), anyString(), anyInt())).thenReturn(false);

        executor.submitAndExecute(203L, 7L);

        verify(indexService).indexBook(eq(203L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(asyncJobService).markSuccess(131L, "knowledge_book", 203L, "indexedChunks=4, createdChunks=4", 0);
        verify(asyncJobService, never()).markSuccess(anyLong(), anyString(), anyLong(), anyString());
        verify(asyncJobService).releaseLock(response);
    }

    @Test
    void shouldEnqueueNewJobWhenQueueIsEnabled() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(14L);
        response.setJobType("KNOWLEDGE_INDEX_BOOK");
        response.setJobKey("book:303");
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexPendingJob(303L, 7L, "ALL")).thenReturn(response);
        when(queueService.enqueue(any())).thenReturn(true);

        executor.submitAndExecute(303L, 7L);

        verify(queueService).enqueue(any());
        verify(asyncJobService).markQueuePublished(14L, 0);
        verify(indexService, never()).indexBook(eq(303L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
    }

    @Test
    void shouldRepublishReusedPendingJobAndRecordPublication() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(24L);
        response.setJobType("KNOWLEDGE_INDEX_BOOK");
        response.setJobKey("book:909:ALL");
        response.setStatus(AsyncJobService.STATUS_PENDING);
        response.setAcquired(false);
        response.setReused(true);
        AsyncJobVO current = new AsyncJobVO();
        current.setId(24L);
        current.setStatus(AsyncJobService.STATUS_PENDING);
        current.setRetryCount(2);
        when(indexService.submitBookIndexPendingJob(909L, 7L, "ALL")).thenReturn(response);
        when(asyncJobService.getJob(24L)).thenReturn(java.util.Optional.of(current));
        when(queueService.enqueue(any())).thenReturn(true);

        AsyncJobSubmitResponse result = executor.submitAndExecute(909L, 7L, "ALL");

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(response);
        verify(queueService).enqueue(org.mockito.ArgumentMatchers.argThat(item -> item.attempt() == 2));
        verify(asyncJobService).markQueuePublished(24L, 2);
        verify(indexService, never()).indexBook(eq(909L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
    }

    @Test
    void shouldFailSubmissionWhenQueuePublishFailsInsteadOfFallingBackToDirectExecution() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(19L);
        response.setJobType("KNOWLEDGE_INDEX_BOOK");
        response.setJobKey("book:404");
        response.setAcquired(true);
        response.setReused(false);
        when(indexService.submitBookIndexPendingJob(404L, 7L, "ALL")).thenReturn(response);
        when(queueService.enqueue(any())).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.submitAndExecute(404L, 7L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("knowledge index queue publish failed");

        verify(queueService).enqueue(any());
        verify(indexService, never()).indexBook(eq(404L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(asyncJobService).markRetryPublishFailed(19L, "knowledge index queue publish failed", 0);
        verify(asyncJobService).releaseLock(response);
    }

    @Test
    void shouldSubmitModeSpecificPendingJobForRankRebuild() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = new KnowledgeIndexJobExecutor(
            indexService,
            asyncJobService,
            new SyncTaskExecutor(),
            repository,
            queueService,
            properties
        );
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(16L);
        response.setJobType("KNOWLEDGE_INDEX_BOOK");
        response.setJobKey("book:505:RANK_INCREMENTAL");
        response.setAcquired(true);
        response.setReused(false);
        when(repository.findBookIdsForKnowledgeRebuild(
            "RANK_INCREMENTAL",
            1,
            properties.getEmbedding().getModel(),
            properties.getEmbedding().getDimension(),
            properties.getIndex().getMaxChapters()
        )).thenReturn(java.util.List.of(505L));
        when(indexService.submitBookIndexPendingJob(505L, 7L, "RANK_INCREMENTAL")).thenReturn(response);
        when(queueService.enqueue(any())).thenReturn(true);

        executor.submitRebuild("RANK_INCREMENTAL", 1, 7L);

        verify(indexService).submitBookIndexPendingJob(505L, 7L, "RANK_INCREMENTAL");
        verify(indexService, never()).submitBookIndexPendingJob(505L, 7L);
    }

    @Test
    void shouldSubmitFullReindexWithEmbeddingRuntimeScope() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        properties.getEmbedding().setModel("tongyi-embedding-vision-flash-2026-03-06");
        properties.getEmbedding().setDimension(768);
        KnowledgeIndexJobExecutor executor = new KnowledgeIndexJobExecutor(
            indexService,
            asyncJobService,
            new SyncTaskExecutor(),
            repository,
            queueService,
            properties
        );
        AsyncJobSubmitResponse response = new AsyncJobSubmitResponse();
        response.setJobId(18L);
        response.setJobType("KNOWLEDGE_INDEX_BOOK");
        response.setJobKey("book:707:FULL_REINDEX:tongyi-embedding-vision-flash-2026-03-06:768");
        response.setAcquired(true);
        response.setReused(false);
        when(repository.findBookIdsForKnowledgeRebuild(
            "FULL_REINDEX",
            1,
            "tongyi-embedding-vision-flash-2026-03-06",
            768,
            properties.getIndex().getMaxChapters()
        )).thenReturn(java.util.List.of(707L));
        when(indexService.submitBookIndexPendingJob(707L, 7L, "FULL_REINDEX")).thenReturn(response);
        when(queueService.enqueue(any())).thenReturn(true);

        executor.submitRebuild("FULL_REINDEX", 1, 7L);

        verify(repository).findBookIdsForKnowledgeRebuild(
            "FULL_REINDEX",
            1,
            "tongyi-embedding-vision-flash-2026-03-06",
            768,
            properties.getIndex().getMaxChapters()
        );
        verify(indexService).submitBookIndexPendingJob(707L, 7L, "FULL_REINDEX");
    }

    @Test
    void shouldSkipQueuedJobWhenAsyncJobIsAlreadySuccessful() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
            17L,
            "KNOWLEDGE_INDEX_BOOK",
            "book:606:RANK_INCREMENTAL",
            "book:606",
            "{\"bookId\":606,\"mode\":\"RANK_INCREMENTAL\"}",
            606L,
            7L,
            "RANK_INCREMENTAL",
            "lock:key",
            "lock:value",
            0,
            "{\"jobId\":17}"
        );
        AsyncJobVO vo = new AsyncJobVO();
        vo.setId(17L);
        vo.setStatus(AsyncJobService.STATUS_SUCCESS);
        when(asyncJobService.getJob(17L)).thenReturn(java.util.Optional.of(vo));

        KnowledgeIndexJobExecutor.QueueConsumeAction action = executor.handleQueuedJob(item);

        org.assertj.core.api.Assertions.assertThat(action).isEqualTo(KnowledgeIndexJobExecutor.QueueConsumeAction.ACK);
        verify(indexService, never()).indexBook(eq(606L), eq("RANK_INCREMENTAL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(indexService, never()).indexBook(606L);
        verify(asyncJobService, never()).markRunning(anyLong());
        verify(asyncJobService).releaseLock(any());
    }

    @Test
    void shouldAckStaleQueuedJobWhenRetryAttemptWasAlreadyScheduled() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
            22L,
            "KNOWLEDGE_INDEX_BOOK",
            "book:707",
            "book:707",
            "{\"bookId\":707}",
            707L,
            7L,
            "ALL",
            "lock:key",
            "lock:value",
            0,
            "{\"jobId\":22}"
        );
        AsyncJobVO vo = new AsyncJobVO();
        vo.setId(22L);
        vo.setStatus(AsyncJobService.STATUS_PENDING);
        vo.setRetryCount(1);
        vo.setQueuePublishedAttempt(1);
        when(asyncJobService.getJob(22L)).thenReturn(java.util.Optional.of(vo));

        KnowledgeIndexJobExecutor.QueueConsumeAction action = executor.handleQueuedJob(item);

        org.assertj.core.api.Assertions.assertThat(action).isEqualTo(KnowledgeIndexJobExecutor.QueueConsumeAction.ACK);
        verify(indexService, never()).indexBook(eq(707L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(asyncJobService, never()).markRunning(anyLong());
        verify(asyncJobService, never()).releaseLock(any());
    }

    @Test
    void shouldRetryQueuedJobAndAckOriginalWhenRabbitRetryPublishSucceeds() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        properties.getIndex().setMaxRetries(3);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
            15L,
            "KNOWLEDGE_INDEX_BOOK",
            "book:404",
            "book:404",
            "{\"bookId\":404}",
            404L,
            7L,
            "ALL",
            "lock:key",
            "lock:value",
            0,
            "{\"jobId\":15}"
        );
        when(indexService.indexBook(eq(404L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class)))
            .thenThrow(new IllegalStateException("qdrant down"));
        when(queueService.retryBackoffSeconds(1)).thenReturn(30L);
        when(queueService.retry(item, 1, 30L)).thenReturn(true);

        KnowledgeIndexJobExecutor.QueueConsumeAction action = executor.handleQueuedJob(item);

        org.assertj.core.api.Assertions.assertThat(action).isEqualTo(KnowledgeIndexJobExecutor.QueueConsumeAction.ACK);
        verify(asyncJobService).markPendingForRetry(15L, "qdrant down", 0);
        verify(asyncJobService).markQueuePublished(15L, 1);
        verify(queueService).retry(item, 1, 30L);
        verify(asyncJobService, never()).markFailed(15L, "qdrant down", 0);
    }

    @Test
    void shouldRequeueQueuedJobWhenRabbitRetryPublishFails() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        properties.getIndex().setMaxRetries(3);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
            20L,
            "KNOWLEDGE_INDEX_BOOK",
            "book:404",
            "book:404",
            "{\"bookId\":404}",
            404L,
            7L,
            "ALL",
            "lock:key",
            "lock:value",
            0,
            "{\"jobId\":20}"
        );
        when(indexService.indexBook(eq(404L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class)))
            .thenThrow(new IllegalStateException("qdrant down"));
        when(queueService.retryBackoffSeconds(1)).thenReturn(30L);
        when(queueService.retry(item, 1, 30L)).thenReturn(false);

        KnowledgeIndexJobExecutor.QueueConsumeAction action = executor.handleQueuedJob(item);

        org.assertj.core.api.Assertions.assertThat(action).isEqualTo(KnowledgeIndexJobExecutor.QueueConsumeAction.REQUEUE);
        verify(asyncJobService).markPendingForRetry(20L, "qdrant down", 0);
        verify(queueService).retry(item, 1, 30L);
        verify(asyncJobService).markRetryPublishFailed(20L, "knowledge index retry publish failed: qdrant down", 1);
        verify(asyncJobService, never()).markFailed(anyLong(), anyString(), anyInt());
    }

    @Test
    void shouldDeadLetterQueuedJobWhenRetryLimitIsExceeded() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        properties.getIndex().setMaxRetries(3);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
            21L,
            "KNOWLEDGE_INDEX_BOOK",
            "book:505",
            "book:505",
            "{\"bookId\":505}",
            505L,
            7L,
            "ALL",
            "lock:key",
            "lock:value",
            3,
            "{\"jobId\":21}"
        );
        when(indexService.indexBook(eq(505L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class)))
            .thenThrow(new IllegalStateException("qdrant down"));

        KnowledgeIndexJobExecutor.QueueConsumeAction action = executor.handleQueuedJob(item);

        org.assertj.core.api.Assertions.assertThat(action).isEqualTo(KnowledgeIndexJobExecutor.QueueConsumeAction.DEAD_LETTER);
        verify(asyncJobService).markFailed(21L, "qdrant down", 3);
        verify(queueService, never()).retry(any(), anyInt(), anyLong());
        verify(asyncJobService).releaseLock(any());
    }

    @Test
    void shouldRecoverRetryPublishFailureByRepublishingRetryMessage() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        properties.getIndex().setMaxRetries(3);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        KnowledgeIndexQueueService.IndexQueueItem item = new KnowledgeIndexQueueService.IndexQueueItem(
            23L,
            "KNOWLEDGE_INDEX_BOOK",
            "book:808",
            "book:808",
            "{\"bookId\":808}",
            808L,
            7L,
            "ALL",
            "lock:key",
            "lock:value",
            0,
            "{\"jobId\":23}"
        );
        AsyncJobVO vo = new AsyncJobVO();
        vo.setId(23L);
        vo.setStatus(AsyncJobService.STATUS_PENDING);
        vo.setRetryCount(1);
        vo.setErrorMessage("knowledge index retry publish failed: qdrant down");
        when(asyncJobService.getJob(23L)).thenReturn(java.util.Optional.of(vo));
        when(queueService.retryBackoffSeconds(1)).thenReturn(30L);
        when(queueService.retry(item.withAttempt(1), 1, 30L)).thenReturn(true);

        KnowledgeIndexJobExecutor.QueueConsumeAction action = executor.handleQueuedJob(item);

        org.assertj.core.api.Assertions.assertThat(action).isEqualTo(KnowledgeIndexJobExecutor.QueueConsumeAction.ACK);
        verify(indexService, never()).indexBook(eq(808L), eq("ALL"), any(KnowledgeIndexService.IndexExecutionGuard.class));
        verify(queueService).retry(item.withAttempt(1), 1, 30L);
        verify(asyncJobService).markQueuePublished(23L, 1);
        verify(asyncJobService, never()).markRunning(anyLong());
    }

    @Test
    void shouldRecoverOrphanPendingJobByRepublishingIt() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        AsyncJobVO job = new AsyncJobVO();
        job.setId(25L);
        job.setJobType("KNOWLEDGE_INDEX_BOOK");
        job.setJobKey("book:909:ALL");
        job.setResourceKey("book:909");
        job.setStatus(AsyncJobService.STATUS_PENDING);
        job.setRetryCount(0);
        job.setTriggerUserId(7L);
        when(asyncJobService.findRecoverableIndexJobs(any(), any(), anyInt())).thenReturn(java.util.List.of(job));
        when(queueService.enqueue(any())).thenReturn(true);

        int recovered = executor.recoverQueuedJobs(20);

        org.assertj.core.api.Assertions.assertThat(recovered).isEqualTo(1);
        verify(queueService).enqueue(any());
        verify(asyncJobService).markQueuePublished(25L, 0);
    }

    @Test
    void shouldResetAndRepublishStaleRunningJob() {
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        AsyncJobService asyncJobService = mock(AsyncJobService.class);
        KnowledgeIndexQueueService queueService = mock(KnowledgeIndexQueueService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        KnowledgeIndexJobExecutor executor = newExecutor(indexService, asyncJobService, queueService, properties);
        AsyncJobVO job = new AsyncJobVO();
        job.setId(26L);
        job.setJobType("KNOWLEDGE_INDEX_BOOK");
        job.setJobKey("book:910:ALL");
        job.setResourceKey("book:910");
        job.setStatus(AsyncJobService.STATUS_RUNNING);
        job.setRetryCount(1);
        job.setTriggerUserId(7L);
        when(asyncJobService.findRecoverableIndexJobs(any(), any(), anyInt())).thenReturn(java.util.List.of(job));
        when(asyncJobService.resetRunningForRecovery(anyLong(), any())).thenReturn(true);
        when(queueService.enqueue(any())).thenReturn(true);

        int recovered = executor.recoverQueuedJobs(20);

        org.assertj.core.api.Assertions.assertThat(recovered).isEqualTo(1);
        verify(asyncJobService).resetRunningForRecovery(eq(26L), any());
        verify(queueService).enqueue(any());
        verify(asyncJobService).markQueuePublished(26L, 2);
    }

    private KnowledgeIndexJobExecutor newExecutor(KnowledgeIndexService indexService, AsyncJobService asyncJobService) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(false);
        return newExecutor(indexService, asyncJobService, mock(KnowledgeIndexQueueService.class), properties);
    }

    private KnowledgeIndexJobExecutor newExecutor(KnowledgeIndexService indexService,
                                                  AsyncJobService asyncJobService,
                                                  KnowledgeIndexQueueService queueService,
                                                  KnowledgeProperties properties) {
        when(asyncJobService.tryMarkRunning(anyLong(), anyInt())).thenReturn(true);
        when(asyncJobService.heartbeatRunning(anyLong(), anyInt())).thenReturn(true);
        when(asyncJobService.renewLock(any(), anyLong())).thenReturn(true);
        when(asyncJobService.markSuccess(anyLong(), anyString(), anyLong(), anyString(), anyInt())).thenReturn(true);
        when(asyncJobService.markPendingForRetry(anyLong(), anyString(), anyInt())).thenReturn(true);
        when(asyncJobService.markFailed(anyLong(), anyString(), anyInt())).thenReturn(true);
        return new KnowledgeIndexJobExecutor(
            indexService,
            asyncJobService,
            new SyncTaskExecutor(),
            mock(KnowledgeRepository.class),
            queueService,
            properties
        );
    }
}

package com.novelanalyzer.modules.asyncjob;

import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.model.AsyncJobEntity;
import com.novelanalyzer.modules.asyncjob.repository.AsyncJobRepository;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobLockService;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncJobServiceTest {

    @Test
    void shouldExecuteSideEffectOnlyWhileRequestedGenerationIsRunning() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobService service = new AsyncJobService(repository, mock(AsyncJobLockService.class));
        AtomicBoolean executed = new AtomicBoolean(false);
        when(repository.lockRunningGeneration(10L, 2)).thenReturn(true);

        String result = service.executeIfRunningGeneration(10L, 2, () -> {
            executed.set(true);
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(executed).isTrue();
        when(repository.lockRunningGeneration(10L, 1)).thenReturn(false);
        assertThatThrownBy(() -> service.executeIfRunningGeneration(10L, 1, () -> "stale"))
            .isInstanceOf(AsyncJobService.GenerationLostException.class);
    }

    @Test
    void shouldReuseRunningJobInsteadOfCreatingAnotherOne() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobLockService lockService = mock(AsyncJobLockService.class);
        AsyncJobService service = new AsyncJobService(repository, lockService);

        AsyncJobEntity running = new AsyncJobEntity();
        running.setId(10L);
        running.setJobType("trend_analysis");
        running.setJobKey("trend-key");
        running.setStatus(AsyncJobService.STATUS_RUNNING);
        when(repository.findLatestByTypeAndKey("trend_analysis", "trend-key")).thenReturn(Optional.of(running));

        AsyncJobSubmitResponse response = service.submitOrReuse(
            "trend_analysis",
            "trend-key",
            "resource-key",
            "{\"x\":1}",
            2L,
            120L
        );

        assertThat(response.getJobId()).isEqualTo(10L);
        assertThat(response.getReused()).isTrue();
        assertThat(response.getAcquired()).isFalse();
        verify(lockService, never()).tryAcquire(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    void shouldCreateRunningJobWhenNoReusableJobExists() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobLockService lockService = mock(AsyncJobLockService.class);
        AsyncJobService service = new AsyncJobService(repository, lockService);

        when(repository.findLatestByTypeAndKey("book_analysis", "analysis-key"))
            .thenReturn(Optional.empty());
        when(lockService.tryAcquire(eq("lock:book_analysis:analysis-key"), org.mockito.ArgumentMatchers.anyString(), eq(180L)))
            .thenReturn(true);
        when(repository.save(org.mockito.ArgumentMatchers.any(AsyncJobEntity.class)))
            .thenAnswer(invocation -> {
                AsyncJobEntity entity = invocation.getArgument(0);
                entity.setId(20L);
                return 20L;
            });

        AsyncJobSubmitResponse response = service.submitOrReuse(
            "book_analysis",
            "analysis-key",
            "analysis-resource",
            "{\"bookId\":1001}",
            3L,
            180L
        );

        assertThat(response.getJobId()).isEqualTo(20L);
        assertThat(response.getStatus()).isEqualTo(AsyncJobService.STATUS_RUNNING);
        assertThat(response.getReused()).isFalse();
        assertThat(response.getAcquired()).isTrue();
        assertThat(response.getLockKey()).isEqualTo("lock:book_analysis:analysis-key");
    }

    @Test
    void shouldReuseSuccessfulJobForDurableAction() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobLockService lockService = mock(AsyncJobLockService.class);
        AsyncJobService service = new AsyncJobService(repository, lockService);

        AsyncJobEntity successful = new AsyncJobEntity();
        successful.setId(21L);
        successful.setJobType("KNOWLEDGE_INDEX_BOOK");
        successful.setJobKey("book:101:action:stable");
        successful.setStatus(AsyncJobService.STATUS_SUCCESS);
        when(repository.findLatestByTypeAndKey("KNOWLEDGE_INDEX_BOOK", "book:101:action:stable"))
            .thenReturn(Optional.of(successful));

        AsyncJobSubmitResponse response = service.submitOrReuseSuccessful(
            "KNOWLEDGE_INDEX_BOOK",
            "book:101:action:stable",
            "book:101",
            "{\"bookId\":101}",
            7L,
            300L
        );

        assertThat(response.getJobId()).isEqualTo(21L);
        assertThat(response.getStatus()).isEqualTo(AsyncJobService.STATUS_SUCCESS);
        assertThat(response.getReused()).isTrue();
        verify(lockService, never()).tryAcquire(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            anyLong()
        );
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldFailClosedWhenSubmissionLockIsHeldBeforeJobBecomesVisible() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobLockService lockService = mock(AsyncJobLockService.class);
        AsyncJobService service = new AsyncJobService(repository, lockService);

        when(repository.findLatestByTypeAndKey("KNOWLEDGE_INDEX_BOOK", "book:101:action:stable"))
            .thenReturn(Optional.empty());
        when(lockService.tryAcquire(
            eq("lock:KNOWLEDGE_INDEX_BOOK:book:101:action:stable"),
            org.mockito.ArgumentMatchers.anyString(),
            eq(300L)
        )).thenReturn(false);

        assertThatThrownBy(() -> service.submitOrReuseSuccessful(
            "KNOWLEDGE_INDEX_BOOK",
            "book:101:action:stable",
            "book:101",
            "{\"bookId\":101}",
            7L,
            300L
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("idempotency lock");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldMarkSuccessAndFailure() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobLockService lockService = mock(AsyncJobLockService.class);
        AsyncJobService service = new AsyncJobService(repository, lockService);

        AsyncJobEntity job = new AsyncJobEntity();
        job.setId(30L);
        job.setStatus(AsyncJobService.STATUS_RUNNING);
        job.setRetryCount(0);
        job.setStartedAt(LocalDateTime.now());
        when(repository.findById(30L)).thenReturn(Optional.of(job));

        service.markSuccess(30L, "analysis_result", 3001L, "done");
        assertThat(job.getStatus()).isEqualTo(AsyncJobService.STATUS_SUCCESS);
        assertThat(job.getResultRefType()).isEqualTo("analysis_result");
        assertThat(job.getResultRefId()).isEqualTo(3001L);

        service.markFailed(30L, "boom");
        assertThat(job.getStatus()).isEqualTo(AsyncJobService.STATUS_FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void shouldTruncateFailureMessageToFitDatabaseColumn() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobLockService lockService = mock(AsyncJobLockService.class);
        AsyncJobService service = new AsyncJobService(repository, lockService);

        AsyncJobEntity job = new AsyncJobEntity();
        job.setId(31L);
        job.setStatus(AsyncJobService.STATUS_RUNNING);
        when(repository.findById(31L)).thenReturn(Optional.of(job));

        service.markFailed(31L, "x".repeat(800));

        assertThat(job.getStatus()).isEqualTo(AsyncJobService.STATUS_FAILED);
        assertThat(job.getErrorMessage()).hasSize(500);
    }

    @Test
    void shouldReleaseLockOnlyWhenSubmitActuallyAcquiredIt() {
        AsyncJobRepository repository = mock(AsyncJobRepository.class);
        AsyncJobLockService lockService = mock(AsyncJobLockService.class);
        AsyncJobService service = new AsyncJobService(repository, lockService);

        AsyncJobSubmitResponse acquired = new AsyncJobSubmitResponse();
        acquired.setAcquired(true);
        acquired.setLockKey("lock:test:key");
        acquired.setLockValue("lock-value");

        AsyncJobSubmitResponse reused = new AsyncJobSubmitResponse();
        reused.setAcquired(false);

        service.releaseLock(acquired);
        service.releaseLock(reused);

        verify(lockService).release("lock:test:key", "lock-value");
    }
}

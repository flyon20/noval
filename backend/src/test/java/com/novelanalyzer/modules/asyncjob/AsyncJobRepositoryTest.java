package com.novelanalyzer.modules.asyncjob;

import com.novelanalyzer.modules.asyncjob.model.AsyncJobEntity;
import com.novelanalyzer.modules.asyncjob.repository.AsyncJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:asyncjobrepo;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
    }
)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql",
        "classpath:sql/phase4-data-h2.sql",
        "classpath:sql/phase5-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class AsyncJobRepositoryTest {

    @Autowired
    private AsyncJobRepository asyncJobRepository;

    @Test
    void shouldSaveAndLoadLatestJobByTypeAndKey() {
        AsyncJobEntity entity = new AsyncJobEntity();
        entity.setJobType("trend_analysis");
        entity.setJobKey("repo:trend:fanqie:male-new:urban-brain:6001:4:deepseek-chat");
        entity.setResourceKey("trend:fanqie:male-new:urban-brain");
        entity.setRequestJson("{\"platform\":\"fanqie\"}");
        entity.setStatus("RUNNING");
        entity.setTriggerUserId(2L);
        entity.setRetryCount(0);
        entity.setStartedAt(LocalDateTime.now());

        Long id = asyncJobRepository.save(entity);

        Optional<AsyncJobEntity> loaded = asyncJobRepository.findLatestByTypeAndKey(
            "trend_analysis",
            "repo:trend:fanqie:male-new:urban-brain:6001:4:deepseek-chat"
        );

        assertThat(id).isNotNull();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo(id);
        assertThat(loaded.get().getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void shouldUpdateSuccessAndFailureFields() {
        AsyncJobEntity entity = new AsyncJobEntity();
        entity.setJobType("book_analysis");
        entity.setJobKey("analysis:1001:deconstruct:3:1:deepseek-chat");
        entity.setStatus("RUNNING");
        entity.setRetryCount(0);
        entity.setStartedAt(LocalDateTime.now());
        Long id = asyncJobRepository.save(entity);

        AsyncJobEntity saved = asyncJobRepository.findById(id).orElseThrow();
        saved.setStatus("SUCCESS");
        saved.setResultRefType("analysis_result");
        saved.setResultRefId(3001L);
        saved.setResultSummary("book analysis done");
        saved.setFinishedAt(LocalDateTime.now());
        asyncJobRepository.updateById(saved);

        AsyncJobEntity loaded = asyncJobRepository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo("SUCCESS");
        assertThat(loaded.getResultRefType()).isEqualTo("analysis_result");
        assertThat(loaded.getResultRefId()).isEqualTo(3001L);
        assertThat(loaded.getResultSummary()).isEqualTo("book analysis done");
    }

    @Test
    void shouldClaimPendingJobOnlyOnceAndRecoverStaleRunningJob() {
        AsyncJobEntity pending = new AsyncJobEntity();
        pending.setJobType("KNOWLEDGE_INDEX_BOOK");
        pending.setJobKey("book:900:ALL");
        pending.setResourceKey("book:900");
        pending.setStatus("PENDING");
        pending.setRetryCount(0);
        Long pendingId = asyncJobRepository.save(pending);

        assertThat(asyncJobRepository.markRunningIfPending(pendingId)).isTrue();
        assertThat(asyncJobRepository.markRunningIfPending(pendingId)).isFalse();

        AsyncJobEntity running = asyncJobRepository.findById(pendingId).orElseThrow();
        running.setStartedAt(LocalDateTime.now().minusHours(1));
        asyncJobRepository.updateById(running);

        assertThat(asyncJobRepository.findRecoverableIndexJobs(
            "KNOWLEDGE_INDEX_BOOK",
            LocalDateTime.now().minusMinutes(10),
            LocalDateTime.now().minusMinutes(10),
            10
        )).extracting(AsyncJobEntity::getId).contains(pendingId);
        assertThat(asyncJobRepository.resetRunningForRecovery(
            pendingId,
            LocalDateTime.now().minusMinutes(10)
        )).isTrue();
        AsyncJobEntity recovered = asyncJobRepository.findById(pendingId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("PENDING");
        assertThat(recovered.getRetryCount()).isEqualTo(1);
        assertThat(asyncJobRepository.markSuccessIfRunning(
            pendingId,
            0,
            "knowledge_book",
            900L,
            "stale worker"
        )).isFalse();
        assertThat(asyncJobRepository.markPublishFailureIfPending(
            pendingId,
            0,
            "stale publish failure"
        )).isFalse();
        assertThat(asyncJobRepository.markPublishFailureIfPending(
            pendingId,
            1,
            "current publish failure"
        )).isTrue();
        assertThat(asyncJobRepository.findById(pendingId).orElseThrow().getErrorMessage())
            .isEqualTo("current publish failure");
        assertThat(asyncJobRepository.markRunningIfPending(pendingId, 1)).isTrue();
        assertThat(asyncJobRepository.isRunningGeneration(pendingId, 1)).isTrue();
        assertThat(asyncJobRepository.isRunningGeneration(pendingId, 0)).isFalse();
        assertThat(asyncJobRepository.lockRunningGeneration(pendingId, 1)).isTrue();
        assertThat(asyncJobRepository.heartbeatRunning(pendingId, 1)).isTrue();
        assertThat(asyncJobRepository.markSuccessIfRunning(
            pendingId,
            1,
            "knowledge_book",
            900L,
            "current worker"
        )).isTrue();
    }

    @Test
    void shouldEnforceOneActiveLogicalJobRowAtDatabaseBoundary() {
        AsyncJobEntity first = new AsyncJobEntity();
        first.setJobType("KNOWLEDGE_INDEX_BOOK");
        first.setJobKey("book:901:ALL");
        first.setStatus("PENDING");
        first.setRetryCount(0);
        asyncJobRepository.save(first);

        AsyncJobEntity duplicate = new AsyncJobEntity();
        duplicate.setJobType("KNOWLEDGE_INDEX_BOOK");
        duplicate.setJobKey("book:901:ALL");
        duplicate.setStatus("PENDING");
        duplicate.setRetryCount(0);

        assertThatThrownBy(() -> asyncJobRepository.save(duplicate))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}

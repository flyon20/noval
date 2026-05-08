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
        entity.setJobKey("trend:fanqie:male-new:urban-brain:6001:4:deepseek-chat");
        entity.setResourceKey("trend:fanqie:male-new:urban-brain");
        entity.setRequestJson("{\"platform\":\"fanqie\"}");
        entity.setStatus("RUNNING");
        entity.setTriggerUserId(2L);
        entity.setRetryCount(0);
        entity.setStartedAt(LocalDateTime.now());

        Long id = asyncJobRepository.save(entity);

        Optional<AsyncJobEntity> loaded = asyncJobRepository.findLatestByTypeAndKey(
            "trend_analysis",
            "trend:fanqie:male-new:urban-brain:6001:4:deepseek-chat"
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
}

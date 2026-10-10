package com.novelanalyzer.modules.system.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResourcePressureServiceTest {

    @Test
    void shouldApplyMemoryAndDiskThresholdsToRuntimeDecisions() {
        KnowledgeProperties properties = new KnowledgeProperties();
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createAsyncJobTable(jdbcTemplate);

        AgentResourcePressureService service = new AgentResourcePressureService(
            properties,
            jdbcTemplate,
            () -> new AgentResourcePressureService.ResourceUsage(93.0d, 81.0d)
        );

        assertThat(service.shouldRejectDeepRun()).isTrue();
        assertThat(service.shouldPauseIndexing()).isTrue();
        assertThat(service.shouldSuppressLowPriorityWork()).isTrue();
    }

    @Test
    void shouldUseQueueBacklogAndOldestAgeToSuppressLowPriorityWork() {
        KnowledgeProperties properties = new KnowledgeProperties();
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createAsyncJobTable(jdbcTemplate);
        for (int index = 0; index < 20; index++) {
            jdbcTemplate.update("""
                insert into async_job(job_type, status, deleted, create_time, update_time)
                values('KNOWLEDGE_INDEX_BOOK', 'PENDING', 0, ?, ?)
                """,
                Timestamp.from(Instant.now().minusSeconds(6 * 60L)),
                Timestamp.from(Instant.now().minusSeconds(6 * 60L))
            );
        }
        AgentResourcePressureService service = new AgentResourcePressureService(
            properties,
            jdbcTemplate,
            () -> new AgentResourcePressureService.ResourceUsage(20.0d, 20.0d)
        );

        AgentResourcePressureService.PressureSnapshot snapshot = service.snapshot();

        assertThat(snapshot.queueBacklogCount()).isEqualTo(20L);
        assertThat(snapshot.queueOldestPendingMinutes()).isGreaterThanOrEqualTo(5L);
        assertThat(service.shouldSuppressLowPriorityWork()).isTrue();
        assertThat(service.shouldRejectDeepRun()).isFalse();
        assertThat(service.shouldPauseIndexing()).isFalse();
    }

    @Test
    void shouldReuseOneSnapshotForBurstPressureChecks() {
        KnowledgeProperties properties = new KnowledgeProperties();
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createAsyncJobTable(jdbcTemplate);
        AtomicInteger probeCalls = new AtomicInteger();
        AgentResourcePressureService service = new AgentResourcePressureService(
            properties,
            jdbcTemplate,
            () -> {
                probeCalls.incrementAndGet();
                return new AgentResourcePressureService.ResourceUsage(20.0d, 20.0d);
            }
        );

        assertThat(service.shouldRejectDeepRun()).isFalse();
        assertThat(service.shouldPauseIndexing()).isFalse();
        assertThat(service.shouldSuppressLowPriorityWork()).isFalse();

        assertThat(probeCalls).hasValue(1);
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:agent-resource-pressure-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        return new JdbcTemplate(dataSource);
    }

    private void createAsyncJobTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
            create table async_job (
                id bigint auto_increment primary key,
                job_type varchar(50) not null,
                status varchar(20) not null,
                deleted tinyint default 0,
                create_time timestamp,
                update_time timestamp
            )
            """);
    }
}

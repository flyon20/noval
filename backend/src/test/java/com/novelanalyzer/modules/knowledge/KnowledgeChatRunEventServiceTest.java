package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAiCacheContinuityService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunEventService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunOutboxCoordinationService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeChatRunEventVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatRunEventServiceTest {

    @AfterEach
    void tearDown() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldCreateOutboxAndSeedAdmissionGuards() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);

        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_outbox",
            Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForList(
            "select mode from ai_chat_run_admission_guard order by mode",
            String.class
        )).containsExactly("DEEP", "FAST");
        assertThat(jdbcTemplate.queryForObject(
            "select count(distinct INDEX_NAME) from INFORMATION_SCHEMA.INDEX_COLUMNS " +
                "where lower(TABLE_NAME) = 'ai_chat_run_outbox' " +
                "and lower(INDEX_NAME) = 'idx_ai_chat_run_outbox_pending'",
            Integer.class
        )).isEqualTo(1);
    }

    @Test
    void shouldSignalRedisCoordinatorAfterOutboxTransactionCommits() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-signal", 7L);
        KnowledgeChatRunOutboxCoordinationService coordinator = mock(
            KnowledgeChatRunOutboxCoordinationService.class
        );
        KnowledgeChatRunEventService service = new KnowledgeChatRunEventService(
            jdbcTemplate,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
            new ObjectMapper(),
            coordinator
        );
        authenticate(7L);

        service.appendEvent(
            "run-signal",
            "EXECUTE",
            "run:run-signal:execute",
            Map.of("status", "PENDING")
        );

        verify(coordinator).signalAfterCommit();
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_outbox where run_id = ?",
            Integer.class,
            "run-signal"
        )).isEqualTo(1);
    }

    @Test
    void shouldAllocateAtomicPerRunSequenceUnderConcurrency() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-concurrent", 7L);
        KnowledgeChatRunEventService service = service(jdbcTemplate);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                int eventIndex = index;
                tasks.add(() -> {
                    AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
                    try {
                        return service.appendEvent(
                            "run-concurrent",
                            "PROGRESS",
                            "event-" + eventIndex,
                            Map.of("index", eventIndex)
                        ).getSequenceNo();
                    } finally {
                        AuthUserHolder.clear();
                    }
                });
            }

            List<Long> sequences = new ArrayList<>();
            for (Future<Long> future : executor.invokeAll(tasks)) {
                sequences.add(future.get());
            }

            assertThat(sequences).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, 16).boxed().toList()
            );
            assertThat(jdbcTemplate.queryForObject(
                "select next_sequence_no from ai_chat_run where run_id = ?",
                Long.class,
                "run-concurrent"
            )).isEqualTo(16L);
            assertThat(jdbcTemplate.queryForObject(
                "select count(1) from ai_chat_run_outbox where run_id = ?",
                Integer.class,
                "run-concurrent"
            )).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnExistingEventForSameIdempotencyKeyWithoutConsumingSequence() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-idempotent", 7L);
        KnowledgeChatRunEventService service = service(jdbcTemplate);
        authenticate(7L);

        KnowledgeChatRunEventVO first = service.appendEvent(
            "run-idempotent",
            "PROGRESS",
            "same-key",
            Map.of("step", 1)
        );
        KnowledgeChatRunEventVO duplicate = service.appendEvent(
            "run-idempotent",
            "PROGRESS",
            "same-key",
            Map.of("step", 2)
        );

        assertThat(duplicate.getEventId()).isEqualTo(first.getEventId());
        assertThat(duplicate.getSequenceNo()).isEqualTo(1L);
        assertThat(duplicate.getPayload()).contains("\"step\":1");
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_event where run_id = ?",
            Integer.class,
            "run-idempotent"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_outbox where run_id = ?",
            Integer.class,
            "run-idempotent"
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select next_sequence_no from ai_chat_run where run_id = ?",
            Long.class,
            "run-idempotent"
        )).isEqualTo(1L);
    }

    @Test
    void shouldListOnlyEventsStrictlyAfterSequenceInAscendingOrder() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-list", 7L);
        KnowledgeChatRunEventService service = service(jdbcTemplate);
        authenticate(7L);
        service.appendEvent("run-list", "STARTED", "key-1", Map.of("step", 1));
        service.appendEvent("run-list", "PROGRESS", "key-2", Map.of("step", 2));
        service.appendEvent("run-list", "COMPLETED", "key-3", Map.of("step", 3));

        List<KnowledgeChatRunEventVO> events = service.listEvents("run-list", 1L, 10);

        assertThat(events).extracting(KnowledgeChatRunEventVO::getSequenceNo)
            .containsExactly(2L, 3L);
        assertThat(events).extracting(KnowledgeChatRunEventVO::getEventType)
            .containsExactly("PROGRESS", "COMPLETED");
    }

    @Test
    void shouldHideAnotherUsersRunForAppendAndRead() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-private", 7L);
        KnowledgeChatRunEventService service = service(jdbcTemplate);
        authenticate(7L);
        service.appendEvent("run-private", "STARTED", "private-key", Map.of());
        authenticate(8L);

        assertThatThrownBy(() -> service.listEvents("run-private", 0L, 10))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service.appendEvent(
            "run-private",
            "PROGRESS",
            "foreign-key",
            Map.of()
        )).isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_event where run_id = ?",
            Integer.class,
            "run-private"
        )).isEqualTo(1);
    }

    @Test
    void shouldAppendAndListOwnedSemanticCheckpointsWithoutAuthUserContext() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-semantic", 7L);
        KnowledgeChatRunEventService service = service(jdbcTemplate);

        KnowledgeChatRunEventVO event = service.appendSemanticCheckpoint(
            7L,
            "run-semantic",
            "TOOL_PREPARED",
            "harness:tool:prepared:call-1",
            Map.of("semanticKey", "call-1", "toolName", "rank.refresh")
        );

        assertThat(event.getSequenceNo()).isEqualTo(1L);
        List<KnowledgeChatRunEventVO> semanticEvents = service.listSemanticCheckpoints(
            7L, "run-semantic", 0L, 10
        );
        assertThat(semanticEvents)
            .extracting(KnowledgeChatRunEventVO::getEventType)
            .containsExactly("TOOL_PREPARED");
        assertThat(semanticEvents.get(0).getPayload()).contains("\"semanticKey\":\"call-1\"");
        com.fasterxml.jackson.databind.JsonNode eventEnvelope = new ObjectMapper()
            .readTree(semanticEvents.get(0).getPayload())
            .path("_event");
        assertThat(eventEnvelope.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(eventEnvelope.path("eventId").asLong()).isEqualTo(event.getEventId());
        assertThat(eventEnvelope.path("runId").asText()).isEqualTo("run-semantic");
        assertThat(eventEnvelope.path("sequence").asLong()).isEqualTo(1L);
        assertThat(eventEnvelope.path("eventType").asText()).isEqualTo("TOOL_PREPARED");
        assertThat(eventEnvelope.path("visibility").asText()).isEqualTo("internal");
        assertThat(eventEnvelope.path("eventIdempotencyKey").asText())
            .isEqualTo("harness:tool:prepared:call-1");

        authenticate(7L);
        List<KnowledgeChatRunEventVO> publicEvents = service.listEvents("run-semantic", 0L, 10);
        assertThat(publicEvents).hasSize(1);
        assertThat(publicEvents.get(0).getSequenceNo()).isEqualTo(1L);
        assertThat(publicEvents.get(0).getPayload()).isEqualTo("{\"internal\":true}");
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_chat_run_outbox where run_id = ?",
            Integer.class,
            "run-semantic"
        )).isZero();
    }

    @Test
    void shouldProjectOnlyCommittedModelCheckpointAfterDurableAppend() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-model-cache", 7L);
        KnowledgeAiCacheContinuityService projection = mock(KnowledgeAiCacheContinuityService.class);
        when(projection.isEnabled()).thenReturn(true);
        KnowledgeChatRunEventService service = new KnowledgeChatRunEventService(
            jdbcTemplate,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
            new ObjectMapper(),
            null,
            projection
        );
        Map<String, Object> preparedPayload = Map.of("semanticKey", "model-1");
        Map<String, Object> committedPayload = Map.of(
            "semanticKey", "model-1",
            "cacheContinuity", Map.of("bodyRedacted", true)
        );

        service.appendSemanticCheckpoint(
            7L,
            "run-model-cache",
            "MODEL_PREPARED",
            "harness:model-prepared:model-1",
            preparedPayload
        );
        KnowledgeChatRunEventVO committed = service.appendSemanticCheckpoint(
            7L,
            "run-model-cache",
            "MODEL_COMMITTED",
            "harness:model-committed:model-1",
            committedPayload
        );

        verify(projection).project(
            7L,
            "run-model-cache",
            committed.getEventId(),
            committedPayload
        );
    }

    @Test
    void shouldPersistPrivateOwnedHarnessRepairAndProgress() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-progress", 7L);
        KnowledgeChatRunEventService service = service(jdbcTemplate);
        Map<String, Object> progress = Map.of(
            "semanticKey", "progress_1", "runId", "run-progress", "userId", "7",
            "progress", Map.of("schemaVersion", "tool-progress-v1", "requestKey", "progress_request_" + "a".repeat(24),
                "attemptId", "b".repeat(24), "ordinal", 2)
        );
        KnowledgeChatRunEventVO first = service.appendSemanticCheckpoint(7L, "run-progress", "TOOL_PROGRESS", "progress-1", progress);
        assertThat(service.appendSemanticCheckpoint(7L, "run-progress", "TOOL_PROGRESS", "progress-1", progress).getEventId())
            .isEqualTo(first.getEventId());
        service.appendSemanticCheckpoint(7L, "run-progress", "HARNESS_REPAIR", "repair-1", Map.of(
            "schemaVersion", "harness-repair-slot-v1", "semanticKey", "repair_1", "runId", "run-progress", "userId", "7", "used", true));
        assertThat(service.listSemanticCheckpoints(7L, "run-progress", 0L, 10)).hasSize(2);
        assertThatThrownBy(() -> service.listSemanticCheckpoints(8L, "run-progress", 0L, 10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.appendSemanticCheckpoint(7L, "run-progress", "TOOL_PROGRESS", "bad", Map.of()))
            .isInstanceOf(BusinessException.class);
        authenticate(7L);
        assertThat(service.listEvents("run-progress", 0L, 10)).extracting(KnowledgeChatRunEventVO::getPayload)
            .containsOnly("{\"internal\":true}");
    }

    @Test
    void shouldRejectForeignOrUnsupportedSemanticCheckpoints() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        applyPhase18Schema(jdbcTemplate);
        insertRun(jdbcTemplate, "run-semantic-private", 7L);
        KnowledgeChatRunEventService service = service(jdbcTemplate);

        assertThatThrownBy(() -> service.appendSemanticCheckpoint(
            8L,
            "run-semantic-private",
            "TOOL_PREPARED",
            "foreign-prepared",
            Map.of()
        )).isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service.appendSemanticCheckpoint(
            7L,
            "run-semantic-private",
            "ANSWERED",
            "unsupported-semantic-event",
            Map.of()
        )).isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getResultCode())
            .isEqualTo(ResultCode.BAD_REQUEST);
    }

    private static KnowledgeChatRunEventService service(JdbcTemplate jdbcTemplate) {
        return new KnowledgeChatRunEventService(
            jdbcTemplate,
            new DataSourceTransactionManager(jdbcTemplate.getDataSource()),
            new ObjectMapper()
        );
    }

    private static void authenticate(long userId) {
        AuthUserHolder.set(AuthUser.of(userId, "writer", Set.of("USER")));
    }

    private static void insertRun(JdbcTemplate jdbcTemplate, String runId, long userId) {
        jdbcTemplate.update(
            "insert into ai_chat_run(run_id, user_id, status, conversation_id, deleted, next_sequence_no) " +
                "values(?, ?, 'RUNNING', ?, 0, 0)",
            runId,
            userId,
            "conv-" + runId
        );
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:chat-run-event-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
            "sa",
            ""
        );
        return new JdbcTemplate(dataSource);
    }

    private static void applyPhase18Schema(JdbcTemplate jdbcTemplate) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource("sql/phase18-agent-harness-conversation-rag-h2.sql")
        );
        DatabasePopulatorUtils.execute(populator, jdbcTemplate.getDataSource());
    }
}

package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.service.KnowledgeMemoryService;
import com.novelanalyzer.modules.knowledge.dto.AiMemoryCandidateRequest;
import com.novelanalyzer.modules.knowledge.vo.AiMemoryVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeMemoryServiceTest {

    @Test
    void shouldCreatePromoteRejectExpireAndSearchScopedMemory() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);

        Long candidateId = service.createCandidate(
            7L,
            900L,
            "conv-1",
            "project",
            "fact",
            "金手指采用三端一体",
            "三端一体设定",
            0.88d,
            "trace-1",
            30
        );
        Long rejectedId = service.createCandidate(
            7L,
            900L,
            "conv-1",
            "user",
            "preference",
            "临时偏好",
            null,
            0.54d,
            "trace-2",
            1
        );

        AiMemoryVO promoted = service.promoteCandidate(candidateId, 7L);
        service.rejectCandidate(rejectedId, 7L);

        assertThat(promoted.getScope()).isEqualTo("project");
        assertThat(promoted.getMemoryType()).isEqualTo("fact");
        assertThat(promoted.getStatus()).isEqualTo("confirmed");
        assertThat(promoted.getLifecycleStatus()).isEqualTo("CONFIRMED");

        List<AiMemoryVO> projectMemories = service.searchConfirmedMemory(7L, 900L, "project", 10);

        assertThat(projectMemories).hasSize(1);
        assertThat(projectMemories.get(0).getContent()).isEqualTo("金手指采用三端一体");

        service.createCandidate(7L, 900L, "conv-1", "project", "fact", "过期候选", null, 0.7d, "trace-3", -1);
        int expired = service.expireCandidates();

        assertThat(expired).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_memory_candidate where content = ?",
            String.class,
            "过期候选"
        )).isEqualTo("expired");
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_memory_candidate where id = ?",
            String.class,
            rejectedId
        )).isEqualTo("rejected");
    }

    @Test
    void shouldNotReturnDeletedOrCrossUserMemory() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        Long ownCandidate = service.createCandidate(7L, 900L, "conv-1", "project", "fact", "自己的设定", null, 0.9d, "trace-1", 30);
        Long otherCandidate = service.createCandidate(8L, 900L, "conv-2", "project", "fact", "别人的设定", null, 0.9d, "trace-2", 30);
        AiMemoryVO own = service.promoteCandidate(ownCandidate, 7L);
        service.promoteCandidate(otherCandidate, 8L);

        jdbcTemplate.update("update ai_memory_item set status = 'deleted', deleted_at = current_timestamp where id = ?", own.getId());

        assertThat(service.searchConfirmedMemory(7L, 900L, "project", 10)).isEmpty();
        assertThat(service.searchConfirmedMemory(8L, 900L, "project", 10))
            .extracting(AiMemoryVO::getContent)
            .containsExactly("别人的设定");
    }

    @Test
    void shouldListReviewAndDeleteMemoryForAdmin() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        Long candidateId = service.createCandidate(7L, 900L, "conv-1", "project", "fact", "candidate memory", null, 0.9d, "trace-1", 30);
        Long otherCandidateId = service.createCandidate(8L, 901L, "conv-2", "user", "preference", "other candidate", null, 0.7d, "trace-2", 30);
        AiMemoryVO confirmed = service.promoteCandidate(otherCandidateId, 8L);

        List<AiMemoryVO> candidates = service.listCandidateMemoriesForAdmin(7L, 900L, "candidate", "project", 20);

        assertThat(candidates).extracting(AiMemoryVO::getContent).containsExactly("candidate memory");

        AiMemoryVO approved = service.reviewCandidateForAdmin(candidateId, "APPROVED");

        assertThat(approved.getStatus()).isEqualTo("confirmed");
        assertThat(service.listMemoriesForAdmin(7L, 900L, "confirmed", "project", 20))
            .extracting(AiMemoryVO::getContent)
            .containsExactly("candidate memory");

        service.deleteMemoryForAdmin(confirmed.getId());

        assertThat(service.listMemoriesForAdmin(8L, 901L, "confirmed", "user", 20)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_memory_item where id = ?",
            String.class,
            confirmed.getId()
        )).isEqualTo("deleted");
    }

    @Test
    void shouldKeepConflictingFactAsCandidateAndReturnOnlyConfirmedEvidenceBackedMemory() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        Long confirmedCandidate = service.createCandidate(
            7L, 900L, "conv-1", "project", "fact", "The protagonist is afraid of fire.",
            null, 0.9d, "trace-confirmed", 30
        );
        jdbcTemplate.update(
            "update ai_memory_candidate set fact_key = ?, provenance_json = ?, evidence_json = ? where id = ?",
            "character.hero.fear", "{\"source\":\"chapter\"}", "[{\"chapterId\":12}]", confirmedCandidate
        );
        AiMemoryVO confirmed = service.promoteCandidate(confirmedCandidate, 7L);

        Long conflictingCandidate = service.createCandidate(
            7L, 900L, "conv-2", "project", "fact", "The protagonist is not afraid of fire.",
            null, 0.87d, "trace-conflict", 30
        );
        jdbcTemplate.update(
            "update ai_memory_candidate set fact_key = ? where id = ?",
            "character.hero.fear", conflictingCandidate
        );

        TransactionTemplate outerTransaction = new TransactionTemplate(
            new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );
        assertThatThrownBy(() -> outerTransaction.execute(status -> service.promoteCandidate(conflictingCandidate, 7L)))
            .isInstanceOfSatisfying(BusinessException.class, conflict -> {
                assertThat(conflict.getResultCode()).isEqualTo(ResultCode.CONFLICT);
                assertThat(conflict).hasMessageContaining("conflicts");
            });

        assertThat(jdbcTemplate.queryForObject(
            "select lifecycle_status from ai_memory_candidate where id = ?", String.class, conflictingCandidate
        )).isEqualTo("CANDIDATE");
        assertThat(jdbcTemplate.queryForObject(
            "select conflicts_with_id from ai_memory_candidate where id = ?", Long.class, conflictingCandidate
        )).isEqualTo(confirmed.getId());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_memory_lifecycle_audit where candidate_id = ? and event_type = 'CONFLICT_DETECTED'",
            Integer.class,
            conflictingCandidate
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_memory_item where lifecycle_status = 'CONFIRMED'", Integer.class
        )).isEqualTo(1);

        List<AiMemoryVO> memories = service.searchConfirmedMemory(7L, 900L, "project", 10);

        assertThat(memories).singleElement().satisfies(memory -> {
            assertThat(memory.getId()).isEqualTo(confirmed.getId());
            assertThat(memory.getStatus()).isEqualTo("confirmed");
            assertThat(memory.getLifecycleStatus()).isEqualTo("CONFIRMED");
            assertThat(memory.getSourceTraceId()).isEqualTo("trace-confirmed");
        });
        assertThat(jdbcTemplate.queryForObject(
            "select evidence_json from ai_memory_item where id = ?", String.class, confirmed.getId()
        )).contains("chapterId");
    }

    @Test
    void shouldReuseAnIdenticalConfirmedFactAndRejectMalformedProvenanceJson() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        AiMemoryCandidateRequest first = memoryRequest("same fact", "project.fact.same", "trace-a");
        AiMemoryCandidateRequest second = memoryRequest("same fact", "project.fact.same", "trace-b");

        AiMemoryVO firstMemory = service.promoteCandidate(service.createCandidate(first), 7L);
        AiMemoryVO reused = service.promoteCandidate(service.createCandidate(second), 7L);

        assertThat(reused.getId()).isEqualTo(firstMemory.getId());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_memory_item where fact_key = ?", Integer.class, "project.fact.same"
        )).isEqualTo(1);

        AiMemoryCandidateRequest invalid = memoryRequest("invalid", "project.fact.invalid", "trace-c");
        invalid.setProvenanceJson("{not-json}");
        assertThatThrownBy(() -> service.createCandidate(invalid))
            .hasMessageContaining("provenanceJson must be compact JSON");
    }

    @Test
    void shouldReuseCandidateWriteAfterRetryWithTheSameIdempotencyKey() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        AiMemoryCandidateRequest request = memoryRequest("retry-safe candidate", "project.fact.retry", "trace-retry");
        request.setCandidateKey("memory-candidate-retry-1");

        Long first = service.createCandidate(request);
        Long second = service.createCandidate(request);

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_memory_candidate where user_id = 7 and candidate_key = ?",
            Integer.class,
            "memory-candidate-retry-1"
        )).isEqualTo(1);
    }

    @Test
    void shouldMarkConfirmedProjectMemoryStaleWithoutPromotingModelCandidate() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        AiMemoryCandidateRequest confirmedRequest = memoryRequest(
            "hero lives in the old city", "character.hero.location", "trace-confirmed");
        AiMemoryVO confirmed = service.promoteCandidate(service.createCandidate(confirmedRequest), 7L);
        AiMemoryCandidateRequest modelCandidate = memoryRequest(
            "hero may have moved", "character.hero.location.pending", "trace-model");
        Long candidateId = service.createCandidate(modelCandidate);

        assertThat(service.markProjectScopeStale(7L, 900L, "generation activated")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap(
            "select status, lifecycle_status, stale_at from ai_memory_item where id = ?", confirmed.getId()))
            .containsEntry("status", "stale")
            .containsEntry("lifecycle_status", "STALE")
            .containsKey("stale_at");
        assertThat(jdbcTemplate.queryForMap(
            "select status, lifecycle_status from ai_memory_candidate where id = ?", candidateId))
            .containsEntry("status", "candidate")
            .containsEntry("lifecycle_status", "CANDIDATE");
        assertThat(service.searchConfirmedMemory(7L, 900L, "project", 10)).isEmpty();
    }

    @Test
    void shouldBackfillLegacyFactKeyBeforeCheckingAContradictoryCandidate() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update(
            "insert into ai_memory_item(user_id, project_id, conversation_id, scope, memory_type, content, confidence, status, lifecycle_status, source_trace_id) "
                + "values(7, 900, 'legacy-conv', 'project', 'fact', ?, 0.9, 'confirmed', 'CONFIRMED', 'legacy-trace')",
            "the protagonist can use fire magic"
        );
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        String factKey = jdbcTemplate.queryForObject(
            "select fact_key from ai_memory_item where source_trace_id = 'legacy-trace'", String.class
        );
        assertThat(factKey).isNotBlank();
        AiMemoryCandidateRequest contradictory = memoryRequest(
            "the protagonist cannot use fire magic", null, "trace-contradiction"
        );

        assertThatThrownBy(() -> service.promoteCandidate(service.createCandidate(contradictory), 7L))
            .hasMessageContaining("conflicts");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_memory_item where lifecycle_status = 'CONFIRMED'", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void shouldRejectCrossTenantProjectAndFactSupersessionAndAllowMatchingReplacement() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        AiMemoryCandidateRequest originalRequest = memoryRequest("original fact", "character.hero.goal", "trace-original");
        AiMemoryVO original = service.promoteCandidate(service.createCandidate(originalRequest), 7L);

        AiMemoryCandidateRequest crossUser = memoryRequest("cross user", "character.hero.goal", "trace-user");
        crossUser.setUserId(8L);
        crossUser.setSupersedesId(original.getId());
        AiMemoryCandidateRequest crossProject = memoryRequest("cross project", "character.hero.goal", "trace-project");
        crossProject.setProjectId(901L);
        crossProject.setSupersedesId(original.getId());
        AiMemoryCandidateRequest crossFact = memoryRequest("cross fact", "character.hero.fear", "trace-fact");
        crossFact.setSupersedesId(original.getId());

        Long crossUserId = service.createCandidate(crossUser);
        Long crossProjectId = service.createCandidate(crossProject);
        Long crossFactId = service.createCandidate(crossFact);
        assertThatThrownBy(() -> service.promoteCandidate(crossUserId, 8L)).hasMessageContaining("fact identity");
        assertThatThrownBy(() -> service.promoteCandidate(crossProjectId, 7L)).hasMessageContaining("fact identity");
        assertThatThrownBy(() -> service.promoteCandidate(crossFactId, 7L)).hasMessageContaining("fact identity");

        AiMemoryCandidateRequest matching = memoryRequest("replacement fact", "character.hero.goal", "trace-replacement");
        matching.setSupersedesId(original.getId());
        AiMemoryVO replacement = service.promoteCandidate(service.createCandidate(matching), 7L);

        assertThat(replacement.getSupersedesId()).isEqualTo(original.getId());
        assertThat(jdbcTemplate.queryForObject(
            "select lifecycle_status from ai_memory_item where id = ?", String.class, original.getId()
        )).isEqualTo("SUPERSEDED");
        assertThat(jdbcTemplate.queryForObject(
            "select lifecycle_status from ai_memory_item where id = ?", String.class, replacement.getId()
        )).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_memory_item where user_id = 7 and project_id = 900 and fact_key = ? and lifecycle_status = 'CONFIRMED'",
            Integer.class,
            "character.hero.goal"
        )).isEqualTo(1);
    }

    @Test
    void shouldAuditTheActualPreRejectionLifecycleState() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        Long candidateId = service.createCandidate(memoryRequest("reject me", "project.fact.reject", "trace-reject"));

        AiMemoryVO rejected = service.reviewCandidateForAdmin(candidateId, "REJECTED");

        assertThat(rejected.getLifecycleStatus()).isEqualTo("REJECTED");
        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "select previous_status, new_status from ai_memory_lifecycle_audit where candidate_id = ? and event_type = 'REJECTED'",
            candidateId
        );
        assertThat(audit)
            .containsEntry("previous_status", "CANDIDATE")
            .containsEntry("new_status", "REJECTED");
    }

    @Test
    void shouldAllowOnlyOneConcurrentReplacementForTheSameConfirmedFact() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeMemoryService service = new KnowledgeMemoryService(jdbcTemplate);
        AiMemoryVO original = service.promoteCandidate(
            service.createCandidate(memoryRequest("original", "character.hero.goal", "trace-original")),
            7L
        );
        AiMemoryCandidateRequest firstRequest = memoryRequest("replacement one", "character.hero.goal", "trace-one");
        firstRequest.setSupersedesId(original.getId());
        AiMemoryCandidateRequest secondRequest = memoryRequest("replacement two", "character.hero.goal", "trace-two");
        secondRequest.setSupersedesId(original.getId());
        Long firstId = service.createCandidate(firstRequest);
        Long secondId = service.createCandidate(secondRequest);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> promoteAfter(start, service, firstId));
            Future<Object> second = executor.submit(() -> promoteAfter(start, service, secondId));
            start.countDown();
            List<Object> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(AiMemoryVO.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ResultCode.class::isInstance).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ai_memory_item where user_id = 7 and project_id = 900 and fact_key = ? and lifecycle_status = 'CONFIRMED'",
                Integer.class,
                "character.hero.goal"
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ai_memory_item where id = ? and lifecycle_status = 'SUPERSEDED'",
                Integer.class,
                original.getId()
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Object promoteAfter(CountDownLatch start,
                                       KnowledgeMemoryService service,
                                       Long candidateId) throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        try {
            return service.promoteCandidate(candidateId, 7L);
        } catch (BusinessException ex) {
            return ex.getResultCode();
        }
    }

    private static AiMemoryCandidateRequest memoryRequest(String content, String factKey, String traceId) {
        AiMemoryCandidateRequest request = new AiMemoryCandidateRequest();
        request.setUserId(7L);
        request.setProjectId(900L);
        request.setConversationId("conv-memory");
        request.setScope("project");
        request.setMemoryType("fact");
        request.setContent(content);
        request.setConfidence(0.9d);
        request.setSourceTraceId(traceId);
        request.setFactKey(factKey);
        request.setTtlDays(30);
        return request;
    }

    static JdbcTemplate jdbcTemplate() {
        JdbcTemplate jdbcTemplate = KnowledgeProjectServiceTest.jdbcTemplate();
        createTables(jdbcTemplate);
        return jdbcTemplate;
    }

    static void createTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("create table ai_memory_candidate (" +
            "id bigint auto_increment primary key," +
            "project_id bigint," +
            "user_id bigint not null," +
            "conversation_id varchar(80)," +
            "scope varchar(30)," +
            "memory_type varchar(60)," +
            "candidate_type varchar(80)," +
            "content clob not null," +
            "summary clob," +
            "confidence double," +
            "status varchar(30) not null default 'candidate'," +
            "lifecycle_status varchar(30) not null default 'CANDIDATE'," +
            "source_trace_id varchar(80)," +
            "fact_key varchar(160)," +
            "candidate_key varchar(200)," +
            "provenance_json clob," +
            "evidence_json clob," +
            "source_evidence_ids_json clob," +
            "source_chapter_versions_json clob," +
            "index_generation varchar(80)," +
            "extractor_version varchar(80)," +
            "supersedes_id bigint," +
            "conflicts_with_id bigint," +
            "legacy_status varchar(30)," +
            "expires_at timestamp," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp)");
        jdbcTemplate.execute("create unique index uk_ai_memory_candidate_idempotency on ai_memory_candidate(user_id, candidate_key)");
        jdbcTemplate.execute("create table ai_memory_item (" +
            "id bigint auto_increment primary key," +
            "user_id bigint not null," +
            "project_id bigint," +
            "conversation_id varchar(80)," +
            "scope varchar(30) not null," +
            "memory_type varchar(60) not null," +
            "content clob not null," +
            "summary clob," +
            "confidence double," +
            "status varchar(30) not null default 'confirmed'," +
            "lifecycle_status varchar(30) not null default 'CONFIRMED'," +
            "source_trace_id varchar(80)," +
            "fact_key varchar(160)," +
            "provenance_json clob," +
            "evidence_json clob," +
            "source_evidence_ids_json clob," +
            "source_chapter_versions_json clob," +
            "index_generation varchar(80)," +
            "extractor_version varchar(80)," +
            "supersedes_id bigint," +
            "confirmed_by bigint," +
            "confirmed_at timestamp," +
            "stale_at timestamp," +
            "legacy_status varchar(30)," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp," +
            "deleted_at timestamp)");
        jdbcTemplate.execute("create table ai_memory_lifecycle_audit (" +
            "id bigint auto_increment primary key," +
            "memory_id bigint," +
            "candidate_id bigint," +
            "event_type varchar(40) not null," +
            "previous_status varchar(30)," +
            "new_status varchar(30) not null," +
            "actor_user_id bigint," +
            "source_trace_id varchar(80)," +
            "details_json clob," +
            "created_at timestamp default current_timestamp)");
    }
}

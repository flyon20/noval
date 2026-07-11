package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.KnowledgeMemoryService;
import com.novelanalyzer.modules.knowledge.vo.AiMemoryVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
            "source_trace_id varchar(80)," +
            "expires_at timestamp," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp)");
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
            "source_trace_id varchar(80)," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp," +
            "deleted_at timestamp)");
    }
}

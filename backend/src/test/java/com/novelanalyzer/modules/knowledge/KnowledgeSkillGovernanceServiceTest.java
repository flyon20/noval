package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateReviewRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeSkillGovernanceService;
import com.novelanalyzer.modules.knowledge.vo.SkillCandidateVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeSkillGovernanceServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldRequireAdminForSkillCandidateAccessAndReview() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("insert into ai_skill_candidate(skill_id, title, status, eval_status) values(?, ?, ?, ?)",
            "webnovel-test", "测试技能", "PENDING", "PASSED");
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);

        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        assertThatThrownBy(service::listCandidates).isInstanceOf(BusinessException.class);

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        List<SkillCandidateVO> candidates = service.listCandidates();

        assertThat(candidates).extracting(SkillCandidateVO::getSkillId).containsExactly("webnovel-test");
        SkillCandidateReviewRequest review = new SkillCandidateReviewRequest();
        review.setDecision("APPROVED");
        service.review(candidates.get(0).getId(), review);
        assertThat(service.listCandidates().get(0).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void shouldRejectApprovalWhenEvalFailed() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("insert into ai_skill_candidate(skill_id, title, status, eval_status) values(?, ?, ?, ?)",
            "bad-skill", "坏技能", "PENDING", "FAILED");
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        SkillCandidateReviewRequest review = new SkillCandidateReviewRequest();
        review.setDecision("APPROVED");

        assertThatThrownBy(() -> service.review(1L, review)).isInstanceOf(BusinessException.class);
    }

    static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:skill-test-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_skill_candidate (" +
            "id bigint auto_increment primary key," +
            "skill_id varchar(120) not null," +
            "title varchar(200) not null," +
            "status varchar(30) not null," +
            "eval_status varchar(30) not null," +
            "review_note varchar(500)," +
            "updated_at timestamp default current_timestamp)");
        return jdbcTemplate;
    }
}

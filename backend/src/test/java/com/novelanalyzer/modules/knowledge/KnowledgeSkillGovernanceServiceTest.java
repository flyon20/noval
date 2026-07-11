package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateCreateRequest;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateReviewRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeSkillGovernanceService;
import com.novelanalyzer.modules.knowledge.vo.RuntimeSkillVO;
import com.novelanalyzer.modules.knowledge.vo.SkillCandidateVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
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

    @Test
    void shouldRejectApprovalWhenStructuredEvalMetricsDoNotMeetGate() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?)
                """,
            "weak-skill",
            "weak eval skill",
            "PENDING",
            "PASSED",
            """
                {"metrics":{"requiredToolPassRate":1.0,"evidencePassRate":0.95,"faithfulnessPassRate":0.75}}
                """);
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        SkillCandidateReviewRequest review = new SkillCandidateReviewRequest();
        review.setDecision("APPROVED");

        assertThatThrownBy(() -> service.review(1L, review))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("faithfulnessPassRate");
    }

    @Test
    void shouldRejectApprovalWhenStructuredEvalMetricColumnsDoNotMeetGate() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, status, eval_status,
                    required_tool_pass_rate, evidence_pass_rate, faithfulness_pass_rate)
                values(?, ?, ?, ?, ?, ?, ?)
                """,
            "weak-column-skill",
            "weak metric column skill",
            "PENDING",
            "PASSED",
            1.0d,
            0.5d,
            0.95d);
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        SkillCandidateReviewRequest review = new SkillCandidateReviewRequest();
        review.setDecision("APPROVED");

        assertThatThrownBy(() -> service.review(1L, review))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("evidencePassRate");
    }

    @Test
    void shouldReturnRuntimeSkillsAndPagedCandidatesForAdminPanel() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        for (int index = 1; index <= 8; index++) {
            jdbcTemplate.update("insert into ai_skill_candidate(skill_id, title, status, eval_status) values(?, ?, ?, ?)",
                "candidate-" + index, "候选技能 " + index, index % 2 == 0 ? "PENDING" : "APPROVED", "PASSED");
        }
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(
            jdbcTemplate,
            () -> List.of(
                runtimeSkill(
                    "webnovel-market-scan",
                    "1.0.0",
                    List.of("market_scan"),
                    List.of("榜单", "趋势")
                )
            )
        );

        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        var page = service.dashboard(1, 5, "PENDING");

        assertThat(page.getRuntimeSkills()).extracting(item -> item.getSkillId()).containsExactly("webnovel-market-scan");
        assertThat(page.getCandidates().getTotal()).isEqualTo(4);
        assertThat(page.getCandidates().getItems()).hasSize(4);
        assertThat(page.getCandidates().getItems()).allMatch(item -> "PENDING".equals(item.getStatus()));
    }

    @Test
    void shouldSurfaceRuntimeSkillReadFailureInsteadOfFallingBackToSupplier() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("create table ai_runtime_skill (id bigint auto_increment primary key, status varchar(20))");
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(
            jdbcTemplate,
            () -> List.of(runtimeSkill("local-fallback", "1.0.0", List.of("market_scan"), List.of("rank")))
        );
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        assertThatThrownBy(() -> service.dashboard(1, 5, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("runtime skill");
    }

    @Test
    void shouldPublishApprovedCandidateAndRollBackPreviousPublishedVersion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?)
                """,
            "market-skill",
            "Market Skill v1",
            "PUBLISHED",
            "PASSED",
            passingEvalJson());
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?)
                """,
            "market-skill",
            "Market Skill v2",
            "APPROVED",
            "PASSED",
            passingEvalJson());
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        SkillCandidateVO published = service.publish(2L);

        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(service.listCandidates())
            .filteredOn(candidate -> candidate.getId() == 1L)
            .singleElement()
            .extracting(SkillCandidateVO::getStatus)
            .isEqualTo("ROLLED_BACK");
    }

    @Test
    void shouldPromotePublishedCandidateIntoActiveRuntimeSkill() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createRuntimeSkillTable(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, content, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?, ?)
                """,
            "market-skill",
            "Market Skill",
            "## Market Skill\nUse fresh rank evidence before synthesis.",
            "APPROVED",
            "PASSED",
            """
                {
                  "version":"2026.07.02",
                  "intents":["market_scan"],
                  "triggers":["rank","trend"],
                  "allowedTools":["rank.lookup","rank.research_pack"],
                  "requiredEvidence":["fresh_rank"],
                  "metrics":{"requiredToolPassRate":1.0,"evidencePassRate":0.95,"faithfulnessPassRate":0.96}
                }
                """);
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        service.publish(1L);

        Map<String, Object> runtime = jdbcTemplate.queryForMap(
            "select * from ai_runtime_skill where skill_id = ?",
            "market-skill"
        );
        assertThat(runtime.get("status")).isEqualTo("ACTIVE");
        assertThat(runtime.get("candidate_id")).isEqualTo(1L);
        assertThat((String) runtime.get("content")).contains("fresh rank evidence");
        assertThat((String) runtime.get("allowed_tools_json")).contains("rank.lookup");
        assertThat((String) runtime.get("required_evidence_json")).contains("fresh_rank");
    }

    @Test
    void shouldRejectPublishWhenEvalGateFails() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?)
                """,
            "weak-publish-skill",
            "Weak Publish Skill",
            "APPROVED",
            "PASSED",
            """
                {"metrics":{"requiredToolPassRate":1.0,"evidencePassRate":0.4,"faithfulnessPassRate":0.95}}
                """);
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("evidencePassRate");
    }

    @Test
    void shouldRejectPublishWhenStructuredEvalResultIsMissing() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("insert into ai_skill_candidate(skill_id, title, status, eval_status) values(?, ?, ?, ?)",
            "missing-eval-skill", "Missing Eval Skill", "APPROVED", "PASSED");
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        assertThatThrownBy(() -> service.publish(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("structured eval result is required");
    }

    @Test
    void shouldDisableAndRollbackPublishedCandidate() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?)
                """,
            "rollback-skill",
            "Rollback Skill v1",
            "ROLLED_BACK",
            "PASSED",
            passingEvalJson());
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?)
                """,
            "rollback-skill",
            "Rollback Skill v2",
            "PUBLISHED",
            "PASSED",
            passingEvalJson());
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));

        SkillCandidateVO disabled = service.disable(2L);
        assertThat(disabled.getStatus()).isEqualTo("DISABLED");

        jdbcTemplate.update("update ai_skill_candidate set status = 'PUBLISHED' where id = 2");
        SkillCandidateVO restored = service.rollback(2L);

        assertThat(restored.getId()).isEqualTo(1L);
        assertThat(restored.getStatus()).isEqualTo("PUBLISHED");
        assertThat(service.listCandidates())
            .filteredOn(candidate -> candidate.getId() == 2L)
            .singleElement()
            .extracting(SkillCandidateVO::getStatus)
            .isEqualTo("ROLLED_BACK");
    }

    @Test
    void shouldDeactivateRuntimeSkillOnDisableAndRestorePreviousRuntimeSkillOnRollback() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createRuntimeSkillTable(jdbcTemplate);
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, content, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?, ?)
                """,
            "rollback-skill",
            "Rollback Skill v1",
            "v1 content",
            "ROLLED_BACK",
            "PASSED",
            passingEvalJson("1.0.0"));
        jdbcTemplate.update("""
                insert into ai_skill_candidate(skill_id, title, content, status, eval_status, eval_result_json)
                values(?, ?, ?, ?, ?, ?)
                """,
            "rollback-skill",
            "Rollback Skill v2",
            "v2 content",
            "PUBLISHED",
            "PASSED",
            passingEvalJson("2.0.0"));
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        service.publish(2L);

        service.disable(2L);
        assertThat(jdbcTemplate.queryForObject(
            "select status from ai_runtime_skill where skill_id = ?",
            String.class,
            "rollback-skill"
        )).isEqualTo("DISABLED");

        jdbcTemplate.update("update ai_skill_candidate set status = 'PUBLISHED' where id = 2");
        SkillCandidateVO restored = service.rollback(2L);

        assertThat(restored.getId()).isEqualTo(1L);
        Map<String, Object> runtime = jdbcTemplate.queryForMap(
            "select * from ai_runtime_skill where skill_id = ?",
            "rollback-skill"
        );
        assertThat(runtime.get("status")).isEqualTo("ACTIVE");
        assertThat(runtime.get("candidate_id")).isEqualTo(1L);
        assertThat(runtime.get("version")).isEqualTo("1.0.0");
        assertThat((String) runtime.get("content")).isEqualTo("v1 content");
    }

    @Test
    void shouldCreateManualSkillCandidateForAdminUpload() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeSkillGovernanceService service = new KnowledgeSkillGovernanceService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(1L, "admin", Set.of("ADMIN")));
        SkillCandidateCreateRequest request = new SkillCandidateCreateRequest();
        request.setSkillId("webnovel-outsourcing-outline");
        request.setTitle("诸天外包大纲技能");
        request.setContent("# 诸天外包大纲技能\n用于三端一体都市脑洞的大纲扩展。");
        request.setEvalResultJson("""
            {"version":"2026.07.04","intents":["mixed_creation_research"],"allowedTools":["rank.lookup"],"metrics":{"requiredToolPassRate":1.0,"evidencePassRate":0.95,"faithfulnessPassRate":0.95}}
            """);

        SkillCandidateVO created = service.createCandidate(request);

        assertThat(created.getSkillId()).isEqualTo("webnovel-outsourcing-outline");
        assertThat(created.getTitle()).isEqualTo("诸天外包大纲技能");
        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(created.getEvalStatus()).isEqualTo("PASSED");
        assertThat(created.getContent()).contains("三端一体");
        assertThat(created.getEvalResultJson()).contains("mixed_creation_research");
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
            "content clob," +
            "status varchar(30) not null," +
            "eval_status varchar(30) not null," +
            "eval_result_json clob," +
            "required_tool_pass_rate double," +
            "evidence_pass_rate double," +
            "faithfulness_pass_rate double," +
            "review_note varchar(500)," +
            "updated_at timestamp default current_timestamp)");
        return jdbcTemplate;
    }

    private static void createRuntimeSkillTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("create table ai_runtime_skill (" +
            "id bigint auto_increment primary key," +
            "candidate_id bigint," +
            "skill_id varchar(120) not null," +
            "version varchar(80)," +
            "title varchar(200)," +
            "content clob not null," +
            "status varchar(30) not null," +
            "intents_json clob," +
            "triggers_json clob," +
            "allowed_tools_json clob," +
            "required_evidence_json clob," +
            "prompt_fragment clob," +
            "guardrails_json clob," +
            "negative_rules_json clob," +
            "output_contract_json clob," +
            "eval_result_json clob," +
            "source_trace_id varchar(80)," +
            "published_at timestamp," +
            "disabled_at timestamp," +
            "updated_at timestamp default current_timestamp)");
    }

    private static RuntimeSkillVO runtimeSkill(String skillId, String version, List<String> intents, List<String> triggers) {
        RuntimeSkillVO vo = new RuntimeSkillVO();
        vo.setSkillId(skillId);
        vo.setVersion(version);
        vo.setIntents(intents);
        vo.setTriggers(triggers);
        return vo;
    }

    private static String passingEvalJson() {
        return passingEvalJson("1.0.0");
    }

    private static String passingEvalJson(String version) {
        return """
            {"version":"%s","metrics":{"requiredToolPassRate":1.0,"evidencePassRate":0.95,"faithfulnessPassRate":0.95}}
            """.formatted(version);
    }
}

package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateReviewRequest;
import com.novelanalyzer.modules.knowledge.vo.SkillCandidateVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class KnowledgeSkillGovernanceService {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeSkillGovernanceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SkillCandidateVO> listCandidates() {
        requireAdmin();
        return jdbcTemplate.query(
            "select id, skill_id, title, status, eval_status, review_note from ai_skill_candidate order by id desc",
            mapper()
        );
    }

    public SkillCandidateVO review(Long id, SkillCandidateReviewRequest request) {
        requireAdmin();
        SkillCandidateVO candidate = find(id);
        String decision = normalizeDecision(request == null ? null : request.getDecision());
        if ("APPROVED".equals(decision) && "FAILED".equalsIgnoreCase(candidate.getEvalStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "failed eval candidate cannot be approved");
        }
        jdbcTemplate.update(
            "update ai_skill_candidate set status = ?, review_note = ?, updated_at = current_timestamp where id = ?",
            decision,
            trimToNull(request == null ? null : request.getNote()),
            id
        );
        return find(id);
    }

    private SkillCandidateVO find(Long id) {
        List<SkillCandidateVO> candidates = jdbcTemplate.query(
            "select id, skill_id, title, status, eval_status, review_note from ai_skill_candidate where id = ?",
            mapper(),
            id
        );
        if (candidates.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "skill candidate not found");
        }
        return candidates.get(0);
    }

    private String normalizeDecision(String value) {
        String decision = trimToNull(value);
        if (decision == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "decision is required");
        }
        decision = decision.toUpperCase(Locale.ROOT);
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported decision");
        }
        return decision;
    }

    private RowMapper<SkillCandidateVO> mapper() {
        return (rs, rowNum) -> {
            SkillCandidateVO vo = new SkillCandidateVO();
            vo.setId(rs.getLong("id"));
            vo.setSkillId(rs.getString("skill_id"));
            vo.setTitle(rs.getString("title"));
            vo.setStatus(rs.getString("status"));
            vo.setEvalStatus(rs.getString("eval_status"));
            vo.setReviewNote(rs.getString("review_note"));
            return vo;
        };
    }

    private void requireAdmin() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || !user.getRoles().contains("ADMIN")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin role required");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

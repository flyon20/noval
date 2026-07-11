package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateCreateRequest;
import com.novelanalyzer.modules.knowledge.dto.SkillCandidateReviewRequest;
import com.novelanalyzer.modules.knowledge.vo.RuntimeSkillVO;
import com.novelanalyzer.modules.knowledge.vo.SkillCandidatePageVO;
import com.novelanalyzer.modules.knowledge.vo.SkillCandidateVO;
import com.novelanalyzer.modules.knowledge.vo.SkillGovernanceDashboardVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class KnowledgeSkillGovernanceService {

    private static final double REQUIRED_TOOL_PASS_RATE_THRESHOLD = 1.0d;
    private static final double EVIDENCE_PASS_RATE_THRESHOLD = 0.9d;
    private static final double FAITHFULNESS_PASS_RATE_THRESHOLD = 0.9d;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final Supplier<List<RuntimeSkillVO>> runtimeSkillSupplier;
    private final ObjectMapper objectMapper;
    private final boolean evalResultJsonColumnAvailable;
    private final boolean contentColumnAvailable;
    private final boolean sourceTraceIdColumnAvailable;
    private final boolean requiredToolPassRateColumnAvailable;
    private final boolean evidencePassRateColumnAvailable;
    private final boolean faithfulnessPassRateColumnAvailable;

    @Autowired
    public KnowledgeSkillGovernanceService(JdbcTemplate jdbcTemplate, LangGraphWorkerClient langGraphWorkerClient) {
        this(jdbcTemplate, langGraphWorkerClient::listRuntimeSkills);
    }

    public KnowledgeSkillGovernanceService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, List::of);
    }

    public KnowledgeSkillGovernanceService(JdbcTemplate jdbcTemplate,
                                           Supplier<List<RuntimeSkillVO>> runtimeSkillSupplier) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeSkillSupplier = runtimeSkillSupplier == null ? List::of : runtimeSkillSupplier;
        this.objectMapper = new ObjectMapper();
        this.evalResultJsonColumnAvailable = hasColumn("ai_skill_candidate", "eval_result_json");
        this.contentColumnAvailable = hasColumn("ai_skill_candidate", "content");
        this.sourceTraceIdColumnAvailable = hasColumn("ai_skill_candidate", "source_trace_id");
        this.requiredToolPassRateColumnAvailable = hasColumn("ai_skill_candidate", "required_tool_pass_rate");
        this.evidencePassRateColumnAvailable = hasColumn("ai_skill_candidate", "evidence_pass_rate");
        this.faithfulnessPassRateColumnAvailable = hasColumn("ai_skill_candidate", "faithfulness_pass_rate");
    }

    public List<SkillCandidateVO> listCandidates() {
        requireAdmin();
        return jdbcTemplate.query(
            selectCandidateColumns() + " from ai_skill_candidate order by id desc",
            mapper()
        );
    }

    public SkillGovernanceDashboardVO dashboard(Integer page, Integer pageSize, String status) {
        requireAdmin();
        SkillGovernanceDashboardVO vo = new SkillGovernanceDashboardVO();
        List<RuntimeSkillVO> runtimeSkills = listRuntimeSkills();
        vo.setRuntimeSkills(runtimeSkills.isEmpty() ? runtimeSkillSupplier.get() : runtimeSkills);
        vo.setCandidates(listCandidatesPage(page, pageSize, status));
        return vo;
    }

    public List<RuntimeSkillVO> listRuntimeSkills() {
        if (!hasTable("ai_runtime_skill")) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                """
                    select candidate_id, skill_id, version, title, content, intents_json, triggers_json,
                           allowed_tools_json, required_evidence_json, prompt_fragment, guardrails_json,
                           negative_rules_json, output_contract_json
                    from ai_runtime_skill
                    where status = 'ACTIVE'
                    order by updated_at desc, id desc
                    """,
                (rs, rowNum) -> mapRuntimeSkill(rs)
            );
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "runtime skill read failed: " + ex.getMessage());
        }
    }

    public SkillCandidateVO createCandidate(SkillCandidateCreateRequest request) {
        requireAdmin();
        String skillId = trimToNull(request == null ? null : request.getSkillId());
        String title = trimToNull(request == null ? null : request.getTitle());
        String content = trimToNull(request == null ? null : request.getContent());
        if (skillId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skillId is required");
        }
        if (title == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "title is required");
        }
        if (contentColumnAvailable && content == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill content is required");
        }
        String evalResultJson = trimToNull(request.getEvalResultJson());
        if (evalResultJson != null) {
            parseEvalResult(evalResultJson);
        }
        SkillCandidateVO candidate = new SkillCandidateVO();
        candidate.setSkillId(skillId);
        candidate.setTitle(title);
        candidate.setContent(content);
        candidate.setEvalResultJson(evalResultJson);
        candidate.setRequiredToolPassRate(request.getRequiredToolPassRate());
        candidate.setEvidencePassRate(request.getEvidencePassRate());
        candidate.setFaithfulnessPassRate(request.getFaithfulnessPassRate());
        candidate.setSourceTraceId(trimToNull(request.getSourceTraceId()));
        String evalStatus = initialEvalStatus(candidate);

        List<String> columns = new ArrayList<>(List.of("skill_id", "title", "status", "eval_status"));
        List<Object> args = new ArrayList<>(List.of(skillId, title, "PENDING", evalStatus));
        if (contentColumnAvailable) {
            columns.add("content");
            args.add(content);
        }
        if (evalResultJsonColumnAvailable) {
            columns.add("eval_result_json");
            args.add(evalResultJson);
        }
        if (requiredToolPassRateColumnAvailable) {
            columns.add("required_tool_pass_rate");
            args.add(request.getRequiredToolPassRate());
        }
        if (evidencePassRateColumnAvailable) {
            columns.add("evidence_pass_rate");
            args.add(request.getEvidencePassRate());
        }
        if (faithfulnessPassRateColumnAvailable) {
            columns.add("faithfulness_pass_rate");
            args.add(request.getFaithfulnessPassRate());
        }
        if (sourceTraceIdColumnAvailable) {
            columns.add("source_trace_id");
            args.add(candidate.getSourceTraceId());
        }
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        jdbcTemplate.update(
            "insert into ai_skill_candidate(" + String.join(", ", columns) + ") values(" + placeholders + ")",
            args.toArray()
        );
        Long id = jdbcTemplate.queryForObject("select max(id) from ai_skill_candidate where skill_id = ?", Long.class, skillId);
        return find(id);
    }

    public SkillCandidatePageVO listCandidatesPage(Integer page, Integer pageSize, String status) {
        requireAdmin();
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);
        long offset = (long) (safePage - 1) * safePageSize;
        List<Object> args = new ArrayList<>();
        String where = buildWhere(status, args);
        Long total = jdbcTemplate.queryForObject(
            "select count(*) from ai_skill_candidate" + where,
            Long.class,
            args.toArray()
        );
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<SkillCandidateVO> items = jdbcTemplate.query(
            selectCandidateColumns() + " from ai_skill_candidate" +
                where + " order by id desc limit ? offset ?",
            mapper(),
            pageArgs.toArray()
        );
        SkillCandidatePageVO pageVO = new SkillCandidatePageVO();
        pageVO.setPage(safePage);
        pageVO.setPageSize(safePageSize);
        pageVO.setTotal(total == null ? 0L : total);
        pageVO.setHasNext((long) safePage * safePageSize < pageVO.getTotal());
        pageVO.setItems(items);
        return pageVO;
    }

    public SkillCandidateVO review(Long id, SkillCandidateReviewRequest request) {
        requireAdmin();
        SkillCandidateVO candidate = find(id);
        String decision = normalizeDecision(request == null ? null : request.getDecision());
        if ("APPROVED".equals(decision) && "FAILED".equalsIgnoreCase(candidate.getEvalStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "failed eval candidate cannot be approved");
        }
        if ("APPROVED".equals(decision)) {
            assertStructuredEvalPassed(candidate);
        }
        jdbcTemplate.update(
            "update ai_skill_candidate set status = ?, review_note = ?, updated_at = current_timestamp where id = ?",
            decision,
            trimToNull(request == null ? null : request.getNote()),
            id
        );
        return find(id);
    }

    public SkillCandidateVO publish(Long id) {
        requireAdmin();
        SkillCandidateVO candidate = find(id);
        assertPublishable(candidate);
        if ("PUBLISHED".equals(candidate.getStatus())) {
            upsertRuntimeSkill(candidate);
            return candidate;
        }
        jdbcTemplate.update(
            "update ai_skill_candidate set status = 'ROLLED_BACK', updated_at = current_timestamp " +
                "where skill_id = ? and status = 'PUBLISHED' and id <> ?",
            candidate.getSkillId(),
            id
        );
        jdbcTemplate.update(
            "update ai_skill_candidate set status = 'PUBLISHED', updated_at = current_timestamp where id = ?",
            id
        );
        SkillCandidateVO published = find(id);
        upsertRuntimeSkill(published);
        return published;
    }

    public SkillCandidateVO disable(Long id) {
        requireAdmin();
        SkillCandidateVO candidate = find(id);
        jdbcTemplate.update(
            "update ai_skill_candidate set status = 'DISABLED', updated_at = current_timestamp where id = ?",
            id
        );
        disableRuntimeSkill(candidate);
        return find(id);
    }

    public SkillCandidateVO rollback(Long id) {
        requireAdmin();
        SkillCandidateVO current = find(id);
        if (!"PUBLISHED".equals(current.getStatus()) && !"DISABLED".equals(current.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "only published or disabled skill can be rolled back");
        }
        List<SkillCandidateVO> previousVersions = jdbcTemplate.query(
            selectCandidateColumns() + " from ai_skill_candidate " +
                "where skill_id = ? and id <> ? and status in ('ROLLED_BACK', 'APPROVED') " +
                "order by case when status = 'ROLLED_BACK' then 0 else 1 end, updated_at desc, id desc limit 1",
            mapper(),
            current.getSkillId(),
            id
        );
        if (previousVersions.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "no previous skill version available for rollback");
        }
        SkillCandidateVO previous = previousVersions.get(0);
        assertStructuredEvalPassed(previous);
        jdbcTemplate.update(
            "update ai_skill_candidate set status = 'ROLLED_BACK', updated_at = current_timestamp where id = ?",
            id
        );
        jdbcTemplate.update(
            "update ai_skill_candidate set status = 'PUBLISHED', updated_at = current_timestamp where id = ?",
            previous.getId()
        );
        disableRuntimeSkill(current);
        SkillCandidateVO restored = find(previous.getId());
        upsertRuntimeSkill(restored);
        return restored;
    }

    private SkillCandidateVO find(Long id) {
        List<SkillCandidateVO> candidates = jdbcTemplate.query(
            selectCandidateColumns() + " from ai_skill_candidate where id = ?",
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

    private void assertPublishable(SkillCandidateVO candidate) {
        if ("FAILED".equalsIgnoreCase(candidate.getEvalStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "failed eval candidate cannot be published");
        }
        if (!"APPROVED".equals(candidate.getStatus()) && !"PUBLISHED".equals(candidate.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "candidate must be approved before publish");
        }
        if (!hasStructuredEvalResult(candidate)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "structured eval result is required before publish");
        }
        if (hasTable("ai_runtime_skill") && trimToNull(candidate.getContent()) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill content is required before publish");
        }
        assertStructuredEvalPassed(candidate);
    }

    private RowMapper<SkillCandidateVO> mapper() {
        return (rs, rowNum) -> {
            SkillCandidateVO vo = new SkillCandidateVO();
            vo.setId(rs.getLong("id"));
            vo.setSkillId(rs.getString("skill_id"));
            vo.setTitle(rs.getString("title"));
            vo.setContent(readStringIfAvailable(rs, "content", contentColumnAvailable));
            vo.setStatus(rs.getString("status"));
            vo.setEvalStatus(rs.getString("eval_status"));
            vo.setReviewNote(rs.getString("review_note"));
            vo.setSourceTraceId(readStringIfAvailable(rs, "source_trace_id", sourceTraceIdColumnAvailable));
            vo.setEvalResultJson(readStringIfAvailable(rs, "eval_result_json", evalResultJsonColumnAvailable));
            vo.setRequiredToolPassRate(readNullableDouble(rs, "required_tool_pass_rate", requiredToolPassRateColumnAvailable));
            vo.setEvidencePassRate(readNullableDouble(rs, "evidence_pass_rate", evidencePassRateColumnAvailable));
            vo.setFaithfulnessPassRate(readNullableDouble(rs, "faithfulness_pass_rate", faithfulnessPassRateColumnAvailable));
            return vo;
        };
    }

    private String selectCandidateColumns() {
        String columns = "select id, skill_id, title, status, eval_status, review_note";
        if (contentColumnAvailable) {
            columns += ", content";
        }
        if (sourceTraceIdColumnAvailable) {
            columns += ", source_trace_id";
        }
        if (evalResultJsonColumnAvailable) {
            columns += ", eval_result_json";
        }
        if (requiredToolPassRateColumnAvailable) {
            columns += ", required_tool_pass_rate";
        }
        if (evidencePassRateColumnAvailable) {
            columns += ", evidence_pass_rate";
        }
        if (faithfulnessPassRateColumnAvailable) {
            columns += ", faithfulness_pass_rate";
        }
        return columns;
    }

    private void upsertRuntimeSkill(SkillCandidateVO candidate) {
        if (!hasTable("ai_runtime_skill")) {
            return;
        }
        Map<String, Object> eval = parseEvalResultOrEmpty(candidate.getEvalResultJson());
        String version = firstString(eval, "version", "skillVersion", "releaseVersion");
        if (version == null) {
            version = "candidate-" + candidate.getId();
        }
        String content = trimToNull(candidate.getContent());
        if (content == null) {
            content = "# " + candidate.getTitle();
        }
        List<String> intents = readStringList(eval, "intents");
        List<String> triggers = readStringList(eval, "triggers");
        List<String> allowedTools = readStringList(eval, "allowedTools", "allowed_tools");
        List<String> requiredEvidence = readStringList(eval, "requiredEvidence", "required_evidence");
        String promptFragment = firstString(eval, "promptFragment", "prompt_fragment");
        String guardrailsJson = writeJson(firstPresent(eval, "guardrails", "guardrailsJson"));
        String negativeRulesJson = writeJson(firstPresent(eval, "negativeRules", "negative_rules"));
        String outputContractJson = writeJson(firstPresent(eval, "outputContract", "output_contract"));
        int existing = countRuntimeSkill(candidate.getSkillId());
        if (existing > 0) {
            jdbcTemplate.update("""
                    update ai_runtime_skill
                    set candidate_id = ?, version = ?, title = ?, content = ?, status = 'ACTIVE',
                        intents_json = ?, triggers_json = ?, allowed_tools_json = ?, required_evidence_json = ?,
                        prompt_fragment = ?, guardrails_json = ?, negative_rules_json = ?, output_contract_json = ?,
                        eval_result_json = ?, source_trace_id = ?, published_at = current_timestamp,
                        disabled_at = null, updated_at = current_timestamp
                    where skill_id = ?
                    """,
                candidate.getId(),
                version,
                candidate.getTitle(),
                content,
                writeJson(intents),
                writeJson(triggers),
                writeJson(allowedTools),
                writeJson(requiredEvidence),
                promptFragment,
                guardrailsJson,
                negativeRulesJson,
                outputContractJson,
                candidate.getEvalResultJson(),
                candidate.getSourceTraceId(),
                candidate.getSkillId()
            );
            return;
        }
        jdbcTemplate.update("""
                insert into ai_runtime_skill(candidate_id, skill_id, version, title, content, status,
                    intents_json, triggers_json, allowed_tools_json, required_evidence_json,
                    prompt_fragment, guardrails_json, negative_rules_json, output_contract_json,
                    eval_result_json, source_trace_id, published_at, updated_at)
                values(?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                """,
            candidate.getId(),
            candidate.getSkillId(),
            version,
            candidate.getTitle(),
            content,
            writeJson(intents),
            writeJson(triggers),
            writeJson(allowedTools),
            writeJson(requiredEvidence),
            promptFragment,
            guardrailsJson,
            negativeRulesJson,
            outputContractJson,
            candidate.getEvalResultJson(),
            candidate.getSourceTraceId()
        );
    }

    private void disableRuntimeSkill(SkillCandidateVO candidate) {
        if (!hasTable("ai_runtime_skill") || candidate == null) {
            return;
        }
        jdbcTemplate.update("""
                update ai_runtime_skill
                set status = 'DISABLED', disabled_at = current_timestamp, updated_at = current_timestamp
                where skill_id = ? and (candidate_id = ? or status = 'ACTIVE')
                """,
            candidate.getSkillId(),
            candidate.getId()
        );
    }

    private int countRuntimeSkill(String skillId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_runtime_skill where skill_id = ?",
            Integer.class,
            skillId
        );
        return count == null ? 0 : count;
    }

    private RuntimeSkillVO mapRuntimeSkill(ResultSet rs) throws SQLException {
        RuntimeSkillVO vo = new RuntimeSkillVO();
        long candidateId = rs.getLong("candidate_id");
        vo.setCandidateId(rs.wasNull() ? null : candidateId);
        vo.setSkillId(rs.getString("skill_id"));
        vo.setVersion(rs.getString("version"));
        vo.setTitle(rs.getString("title"));
        vo.setContent(rs.getString("content"));
        vo.setIntents(parseStringList(rs.getString("intents_json")));
        vo.setTriggers(parseStringList(rs.getString("triggers_json")));
        vo.setAllowedTools(parseStringList(rs.getString("allowed_tools_json")));
        vo.setRequiredEvidence(parseStringList(rs.getString("required_evidence_json")));
        vo.setPromptFragment(rs.getString("prompt_fragment"));
        vo.setGuardrails(rs.getString("guardrails_json"));
        vo.setNegativeRules(rs.getString("negative_rules_json"));
        vo.setOutputContract(rs.getString("output_contract_json"));
        vo.setSource("backend");
        return vo;
    }

    private void assertStructuredEvalPassed(SkillCandidateVO candidate) {
        String evalResultJson = trimToNull(candidate.getEvalResultJson());
        if (evalResultJson == null && hasMetricColumns(candidate)) {
            assertMetricAtLeast(candidate.getRequiredToolPassRate(), "requiredToolPassRate", REQUIRED_TOOL_PASS_RATE_THRESHOLD);
            assertMetricAtLeast(candidate.getEvidencePassRate(), "evidencePassRate", EVIDENCE_PASS_RATE_THRESHOLD);
            assertMetricAtLeast(candidate.getFaithfulnessPassRate(), "faithfulnessPassRate", FAITHFULNESS_PASS_RATE_THRESHOLD);
            return;
        }
        if (evalResultJson == null) {
            return;
        }
        Map<String, Object> result = parseEvalResult(evalResultJson);
        Object metricsObject = result.getOrDefault("metrics", result);
        if (!(metricsObject instanceof Map<?, ?> metrics)) {
            return;
        }
        assertMetricAtLeast(metrics, "requiredToolPassRate", REQUIRED_TOOL_PASS_RATE_THRESHOLD);
        assertMetricAtLeast(metrics, "evidencePassRate", EVIDENCE_PASS_RATE_THRESHOLD);
        assertMetricAtLeast(metrics, "faithfulnessPassRate", FAITHFULNESS_PASS_RATE_THRESHOLD);
    }

    private boolean hasMetricColumns(SkillCandidateVO candidate) {
        return candidate.getRequiredToolPassRate() != null
            || candidate.getEvidencePassRate() != null
            || candidate.getFaithfulnessPassRate() != null;
    }

    private boolean hasStructuredEvalResult(SkillCandidateVO candidate) {
        return trimToNull(candidate.getEvalResultJson()) != null || hasMetricColumns(candidate);
    }

    private String initialEvalStatus(SkillCandidateVO candidate) {
        if (!hasStructuredEvalResult(candidate)) {
            return "PENDING";
        }
        try {
            assertStructuredEvalPassed(candidate);
            return "PASSED";
        } catch (BusinessException ex) {
            return "FAILED";
        }
    }

    private Map<String, Object> parseEvalResult(String evalResultJson) {
        try {
            return objectMapper.readValue(evalResultJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill eval result json is invalid");
        }
    }

    private Map<String, Object> parseEvalResultOrEmpty(String evalResultJson) {
        String raw = trimToNull(evalResultJson);
        if (raw == null) {
            return Collections.emptyMap();
        }
        return parseEvalResult(raw);
    }

    private List<String> readStringList(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            String text = item == null ? null : trimToNull(String.valueOf(item));
            if (text != null && !values.contains(text)) {
                values.add(text);
            }
        }
        return values;
    }

    private List<String> parseStringList(String rawJson) {
        String raw = trimToNull(rawJson);
        if (raw == null) {
            return List.of();
        }
        try {
            List<?> values = objectMapper.readValue(raw, List.class);
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                String text = value == null ? null : trimToNull(String.valueOf(value));
                if (text != null) {
                    result.add(text);
                }
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "failed to write skill runtime json");
        }
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        Object skill = map.get("skill");
        if (skill instanceof Map<?, ?> nested) {
            for (String key : keys) {
                if (nested.containsKey(key) && nested.get(key) != null) {
                    return nested.get(key);
                }
            }
        }
        return null;
    }

    private String firstString(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        String text = value == null ? null : trimToNull(String.valueOf(value));
        return text;
    }

    private void assertMetricAtLeast(Map<?, ?> metrics, String metricName, double threshold) {
        assertMetricAtLeast(metricValue(metrics.get(metricName)), metricName, threshold);
    }

    private void assertMetricAtLeast(Double value, String metricName, double threshold) {
        if (value == null || value < threshold) {
            throw new BusinessException(
                ResultCode.BAD_REQUEST,
                "skill eval metric " + metricName + " must be >= " + threshold
            );
        }
    }

    private Double metricValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean hasColumn(String tableName, String columnName) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
                    if (rs.next()) {
                        return true;
                    }
                }
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))) {
                    return rs.next();
                }
            }));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean hasTable(String tableName) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
                try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, null)) {
                    if (rs.next()) {
                        return true;
                    }
                }
                try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName.toUpperCase(Locale.ROOT), null)) {
                    return rs.next();
                }
            }));
        } catch (Exception ex) {
            return false;
        }
    }

    private String readStringIfAvailable(ResultSet rs, String columnName, boolean available) throws SQLException {
        if (!available) {
            return null;
        }
        return rs.getString(columnName);
    }

    private Double readNullableDouble(ResultSet rs, String columnName, boolean available) throws SQLException {
        if (!available) {
            return null;
        }
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    private String buildWhere(String status, List<Object> args) {
        String normalized = trimToNull(status);
        if (normalized == null) {
            return "";
        }
        args.add(normalized.toUpperCase(Locale.ROOT));
        return " where status = ?";
    }

    private int normalizePage(Integer page) {
        if (page == null || page <= 0) {
            return 1;
        }
        return Math.min(page, 10000);
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 50);
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

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
import com.novelanalyzer.modules.knowledge.vo.SkillShortcutVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class KnowledgeSkillGovernanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeSkillGovernanceService.class);
    private static final double REQUIRED_TOOL_PASS_RATE_THRESHOLD = 1.0d;
    private static final double EVIDENCE_PASS_RATE_THRESHOLD = 0.9d;
    private static final double FAITHFULNESS_PASS_RATE_THRESHOLD = 0.9d;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};
    private static final Set<String> SKILL_LIFECYCLE_STATUSES = Set.of("DRAFT", "APPROVED", "ACTIVE", "REVOKED");
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,119}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> ALLOWED_INTENTS = Set.of(
        "market_scan", "opening_strategy", "book_breakdown", "outline_building", "chapter_outline",
        "inspiration_expand", "character_design", "worldbuilding", "revision_advice", "followup_context",
        "mixed_creation_research", "out_of_scope"
    );
    private static final Set<String> ALLOWED_CAPABILITIES = Set.of(
        "market.read", "market.refresh", "book.read", "project.resolve", "project.retrieve",
        "project.continuity.read", "memory.project.read", "skill.activate", "review.reader", "review.editor"
    );
    private static final Set<String> JSON_SCHEMA_TYPES = Set.of(
        "null", "boolean", "object", "array", "number", "integer", "string"
    );
    private static final Set<String> PROMPT_METADATA_KEYS = Set.of(
        "requiredEvidence", "required_evidence", "promptFragment", "prompt_fragment",
        "qualityChecklist", "quality_checklist", "guardrails", "negativeRules", "negative_rules",
        "outputContract", "output_contract", "examples"
    );
    private static final int MAX_SCHEMA_CHARS = 32_768;
    private static final int MAX_SCHEMA_DEPTH = 12;
    private static final int MAX_SCHEMA_NODES = 512;
    private static final int MAX_SCHEMA_MAP_ENTRIES = 128;
    private static final int MAX_PROMPT_METADATA_ITEMS = 64;
    private static final int MAX_PROMPT_EXAMPLES = 32;
    private static final int MAX_PROMPT_METADATA_ITEM_CHARS = 4_000;
    private static final int MAX_PROMPT_METADATA_TOTAL_CHARS = 16_000;
    private static final int MAX_RUNTIME_SCALAR_PROMPT_CHARS = 4_000;
    private static final List<Pattern> PROMPT_INJECTION_PATTERNS = List.of(
        Pattern.compile("(?is)\\bignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+(?:instructions?|rules?|prompts?)\\b"),
        Pattern.compile("(?is)\\b(?:reveal|show|print|leak|expose)\\b.{0,80}\\b(?:system|developer)\\s+prompt\\b"),
        Pattern.compile("(?is)<\\|(?:system|developer)\\|>|\\[system\\s+message\\]"),
        Pattern.compile("(?:忽略|无视).{0,32}(?:之前|上述|所有).{0,32}(?:指令|规则|提示)"),
        Pattern.compile("(?:泄露|暴露|显示|输出).{0,48}(?:系统提示|开发者提示|密钥|令牌)")
    );

    private final JdbcTemplate jdbcTemplate;
    private final Supplier<List<RuntimeSkillVO>> runtimeSkillSupplier;
    private final ObjectMapper objectMapper;
    private final AgentSkillMarkdownParser skillMarkdownParser;
    private final TransactionTemplate transactionTemplate;
    private final boolean evalResultJsonColumnAvailable;
    private final boolean contentColumnAvailable;
    private final boolean sourceTraceIdColumnAvailable;
    private final boolean requiredToolPassRateColumnAvailable;
    private final boolean evidencePassRateColumnAvailable;
    private final boolean faithfulnessPassRateColumnAvailable;
    private final boolean lifecycleStatusColumnAvailable;
    private final boolean versionColumnAvailable;
    private final boolean contentHashColumnAvailable;
    private final boolean inputSchemaColumnAvailable;
    private final boolean outputSchemaColumnAvailable;
    private final boolean descriptionColumnAvailable;
    private final boolean requestedCapabilitiesColumnAvailable;
    private final boolean skillMetadataColumnAvailable;
    private final boolean rolloutPolicyColumnAvailable;
    private final boolean runtimeContentHashColumnAvailable;
    private final boolean runtimeInputSchemaColumnAvailable;
    private final boolean runtimeOutputSchemaColumnAvailable;
    private final boolean runtimeRolloutPolicyColumnAvailable;
    private final boolean runtimeActivatedByColumnAvailable;
    private final boolean runtimeActivatedAtColumnAvailable;
    private final boolean runtimeRollbackVersionColumnAvailable;
    private final boolean runtimeDescriptionColumnAvailable;
    private final boolean runtimeRequestedCapabilitiesColumnAvailable;
    private final boolean runtimeSkillMetadataColumnAvailable;

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
        this.skillMarkdownParser = new AgentSkillMarkdownParser();
        this.transactionTemplate = new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource(), "dataSource is required"))
        );
        this.evalResultJsonColumnAvailable = hasColumn("ai_skill_candidate", "eval_result_json");
        this.contentColumnAvailable = hasColumn("ai_skill_candidate", "content");
        this.sourceTraceIdColumnAvailable = hasColumn("ai_skill_candidate", "source_trace_id");
        this.requiredToolPassRateColumnAvailable = hasColumn("ai_skill_candidate", "required_tool_pass_rate");
        this.evidencePassRateColumnAvailable = hasColumn("ai_skill_candidate", "evidence_pass_rate");
        this.faithfulnessPassRateColumnAvailable = hasColumn("ai_skill_candidate", "faithfulness_pass_rate");
        this.lifecycleStatusColumnAvailable = hasColumn("ai_skill_candidate", "lifecycle_status");
        this.versionColumnAvailable = hasColumn("ai_skill_candidate", "version");
        this.contentHashColumnAvailable = hasColumn("ai_skill_candidate", "content_hash");
        this.inputSchemaColumnAvailable = hasColumn("ai_skill_candidate", "input_schema_json");
        this.outputSchemaColumnAvailable = hasColumn("ai_skill_candidate", "output_schema_json");
        this.descriptionColumnAvailable = hasColumn("ai_skill_candidate", "description");
        this.requestedCapabilitiesColumnAvailable = hasColumn("ai_skill_candidate", "requested_capabilities_json");
        this.skillMetadataColumnAvailable = hasColumn("ai_skill_candidate", "skill_metadata_json");
        this.rolloutPolicyColumnAvailable = hasColumn("ai_skill_candidate", "rollout_policy_json");
        this.runtimeContentHashColumnAvailable = hasColumn("ai_runtime_skill", "content_hash");
        this.runtimeInputSchemaColumnAvailable = hasColumn("ai_runtime_skill", "input_schema_json");
        this.runtimeOutputSchemaColumnAvailable = hasColumn("ai_runtime_skill", "output_schema_json");
        this.runtimeRolloutPolicyColumnAvailable = hasColumn("ai_runtime_skill", "rollout_policy_json");
        this.runtimeActivatedByColumnAvailable = hasColumn("ai_runtime_skill", "activated_by");
        this.runtimeActivatedAtColumnAvailable = hasColumn("ai_runtime_skill", "activated_at");
        this.runtimeRollbackVersionColumnAvailable = hasColumn("ai_runtime_skill", "rollback_version");
        this.runtimeDescriptionColumnAvailable = hasColumn("ai_runtime_skill", "description");
        this.runtimeRequestedCapabilitiesColumnAvailable = hasColumn("ai_runtime_skill", "requested_capabilities_json");
        this.runtimeSkillMetadataColumnAvailable = hasColumn("ai_runtime_skill", "skill_metadata_json");
    }

    public List<SkillCandidateVO> listCandidates() {
        requireAdmin();
        return jdbcTemplate.query(selectCandidateColumns() + " from ai_skill_candidate order by id desc", mapper());
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
            List<RuntimeSkillVO> runtimeSkills = jdbcTemplate.query(runtimeSelectColumns() + " from ai_runtime_skill "
                + "where status = 'ACTIVE' order by updated_at desc, id desc", (rs, rowNum) -> mapRuntimeSkill(rs));
            return runtimeSkills.stream().filter(this::runtimeContentIsTrusted).toList();
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "runtime skill read failed: " + ex.getMessage());
        }
    }

    public List<SkillShortcutVO> listSkillShortcuts() {
        Map<String, RuntimeSkillVO> runtimeSkillsById = new LinkedHashMap<>();
        List<RuntimeSkillVO> workerSkills = List.of();
        try {
            List<RuntimeSkillVO> suppliedSkills = runtimeSkillSupplier.get();
            workerSkills = suppliedSkills == null ? List.of() : suppliedSkills;
        } catch (RuntimeException ex) {
            LOGGER.warn("worker runtime skills unavailable while listing shortcuts: {}", ex.getMessage());
        }
        for (RuntimeSkillVO runtimeSkill : workerSkills) {
            String skillId = runtimeSkill == null ? null : trimToNull(runtimeSkill.getSkillId());
            if (skillId != null) {
                runtimeSkillsById.put(skillId, runtimeSkill);
            }
        }
        List<RuntimeSkillVO> databaseSkills;
        try {
            databaseSkills = listRuntimeSkills();
        } catch (RuntimeException ex) {
            LOGGER.warn("database runtime skills unavailable while listing shortcuts: {}", ex.getMessage());
            if (runtimeSkillsById.isEmpty()) {
                throw ex;
            }
            databaseSkills = List.of();
        }
        for (RuntimeSkillVO runtimeSkill : databaseSkills) {
            String skillId = runtimeSkill == null ? null : trimToNull(runtimeSkill.getSkillId());
            if (skillId != null) {
                runtimeSkillsById.put(skillId, runtimeSkill);
            }
        }
        return runtimeSkillsById.values().stream()
            .filter(skill -> trimToNull(skill.getSkillId()) != null)
            .filter(this::shortcutEnabled)
            .sorted(Comparator
                .comparingInt((RuntimeSkillVO skill) -> shortcutOrder(skill))
                .thenComparing(skill -> skill.getSkillId().trim()))
            .map(this::toSkillShortcut)
            .toList();
    }

    private SkillShortcutVO toSkillShortcut(RuntimeSkillVO runtimeSkill) {
        SkillShortcutVO shortcut = new SkillShortcutVO();
        shortcut.setSkillId(runtimeSkill.getSkillId());
        Object shortcutLabel = shortcutMetadataValue(runtimeSkill, "shortcutLabel");
        String label = shortcutLabel instanceof String text ? trimToNull(text) : null;
        shortcut.setTitle(label != null
            ? label
            : trimToNull(runtimeSkill.getTitle()) == null
                ? runtimeSkill.getSkillId()
                : runtimeSkill.getTitle().trim());
        shortcut.setDescription(trimToNull(runtimeSkill.getDescription()));
        List<String> appliesTo = runtimeSkill.getAppliesTo();
        if (appliesTo == null || appliesTo.isEmpty()) {
            appliesTo = runtimeSkill.getIntents();
        }
        shortcut.setAppliesTo(appliesTo);
        return shortcut;
    }

    private boolean shortcutEnabled(RuntimeSkillVO runtimeSkill) {
        return Boolean.TRUE.equals(shortcutMetadataValue(runtimeSkill, "shortcutEnabled"));
    }

    private int shortcutOrder(RuntimeSkillVO runtimeSkill) {
        Object value = shortcutMetadataValue(runtimeSkill, "shortcutOrder");
        if (!(value instanceof Number number)) {
            return Integer.MAX_VALUE;
        }
        int order = number.intValue();
        return order >= 0 && order <= 10_000 ? order : Integer.MAX_VALUE;
    }

    private Object shortcutMetadataValue(RuntimeSkillVO runtimeSkill, String key) {
        Map<String, Object> metadata = runtimeSkill.getSkillMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        if (metadata.containsKey(key)) {
            return metadata.get(key);
        }
        Object nested = metadata.get("metadata");
        return nested instanceof Map<?, ?> nestedMetadata ? nestedMetadata.get(key) : null;
    }

    public SkillCandidateVO createCandidate(SkillCandidateCreateRequest request) {
        requireAdmin();
        requireGovernedCandidateSchema();
        SkillImport skillImport = resolveSkillImport(request);
        String skillId = skillImport.skillId();
        validateSkillId(skillId);
        String title = skillImport.title();
        String content = skillImport.instructions();
        String evalResultJson = trimToNull(request == null ? null : request.getEvalResultJson());
        Map<String, Object> eval = evalResultJson == null ? Collections.emptyMap() : parseEvalResult(evalResultJson);
        SkillContract contract = resolveCreateContract(request, eval, content, skillImport);
        validateSkillContract(skillId, contract, content, eval);

        SkillCandidateVO candidate = new SkillCandidateVO();
        candidate.setSkillId(skillId);
        candidate.setTitle(title);
        candidate.setDescription(skillImport.description());
        candidate.setContent(content);
        candidate.setEvalResultJson(evalResultJson);
        candidate.setRequiredToolPassRate(request == null ? null : request.getRequiredToolPassRate());
        candidate.setEvidencePassRate(request == null ? null : request.getEvidencePassRate());
        candidate.setFaithfulnessPassRate(request == null ? null : request.getFaithfulnessPassRate());
        candidate.setSourceTraceId(trimToNull(request == null ? null : request.getSourceTraceId()));
        candidate.setVersion(contract.version());
        candidate.setContentHash(contract.contentHash());
        candidate.setInputSchemaJson(contract.inputSchemaJson());
        candidate.setOutputSchemaJson(contract.outputSchemaJson());
        candidate.setRequestedCapabilitiesJson(writeJson(contract.requestedCapabilities()));
        candidate.setSkillMetadataJson(skillImport.skillMetadataJson());
        candidate.setRolloutPolicyJson(contract.rolloutPolicyJson());
        String evalStatus = initialEvalStatus(candidate);

        List<String> columns = new ArrayList<>(List.of("skill_id", "title", "status", "eval_status"));
        List<Object> values = new ArrayList<>(List.of(skillId, title, "PENDING", evalStatus));
        addCandidateColumn(columns, values, "content", content, contentColumnAvailable);
        addCandidateColumn(columns, values, "eval_result_json", evalResultJson, evalResultJsonColumnAvailable);
        addCandidateColumn(columns, values, "required_tool_pass_rate", candidate.getRequiredToolPassRate(), requiredToolPassRateColumnAvailable);
        addCandidateColumn(columns, values, "evidence_pass_rate", candidate.getEvidencePassRate(), evidencePassRateColumnAvailable);
        addCandidateColumn(columns, values, "faithfulness_pass_rate", candidate.getFaithfulnessPassRate(), faithfulnessPassRateColumnAvailable);
        addCandidateColumn(columns, values, "source_trace_id", candidate.getSourceTraceId(), sourceTraceIdColumnAvailable);
        addCandidateColumn(columns, values, "lifecycle_status", "DRAFT", lifecycleStatusColumnAvailable);
        addCandidateColumn(columns, values, "version", contract.version(), versionColumnAvailable);
        addCandidateColumn(columns, values, "content_hash", contract.contentHash(), contentHashColumnAvailable);
        addCandidateColumn(columns, values, "input_schema_json", contract.inputSchemaJson(), inputSchemaColumnAvailable);
        addCandidateColumn(columns, values, "output_schema_json", contract.outputSchemaJson(), outputSchemaColumnAvailable);
        addCandidateColumn(columns, values, "description", skillImport.description(), descriptionColumnAvailable);
        addCandidateColumn(
            columns,
            values,
            "requested_capabilities_json",
            writeJson(contract.requestedCapabilities()),
            requestedCapabilitiesColumnAvailable
        );
        addCandidateColumn(
            columns,
            values,
            "skill_metadata_json",
            skillImport.skillMetadataJson(),
            skillMetadataColumnAvailable
        );
        addCandidateColumn(columns, values, "rollout_policy_json", contract.rolloutPolicyJson(), rolloutPolicyColumnAvailable);

        Long id = insertReturningId("ai_skill_candidate", columns, values, "skill candidate id missing");
        SkillCandidateVO created = find(id);
        auditSkill(created, null, "CREATED", null, "DRAFT", Map.of("legacyStatus", "PENDING"));
        return created;
    }

    public SkillCandidatePageVO listCandidatesPage(Integer page, Integer pageSize, String status) {
        requireAdmin();
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);
        long offset = (long) (safePage - 1) * safePageSize;
        List<Object> args = new ArrayList<>();
        String where = buildWhere(status, args);
        Long total = jdbcTemplate.queryForObject("select count(*) from ai_skill_candidate" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePageSize);
        pageArgs.add(offset);
        List<SkillCandidateVO> items = jdbcTemplate.query(selectCandidateColumns() + " from ai_skill_candidate" + where
            + " order by id desc limit ? offset ?", mapper(), pageArgs.toArray());
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
        requireGovernedCandidateSchema();
        String decision = normalizeDecision(request == null ? null : request.getDecision());
        return inTransaction(() -> {
            SkillCandidateVO candidate = lockAndFind(id, true);
            if ("APPROVED".equals(decision)) {
                requireLifecycle(candidate, "DRAFT", "only draft candidate can be approved");
                if ("FAILED".equalsIgnoreCase(candidate.getEvalStatus())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "failed eval candidate cannot be approved");
                }
                assertStructuredEvalPassed(candidate);
                validateCandidateContract(candidate);
                transitionCandidateStatus(candidate.getId(), "DRAFT", "APPROVED", "APPROVED",
                    trimToNull(request == null ? null : request.getNote()));
                setApprovalMetadata(candidate.getId(), currentAdminId());
                SkillCandidateVO approved = find(candidate.getId());
                auditSkill(approved, null, "APPROVED", candidate.getLifecycleStatus(), "APPROVED", Map.of());
                return approved;
            }
            if ("REVOKED".equals(candidate.getLifecycleStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "revoked candidate cannot be reviewed again");
            }
            String previousStatus = candidate.getLifecycleStatus();
            if ("ACTIVE".equals(previousStatus)) {
                requireGovernedRuntimeSchema();
                assertOnlyActiveCandidate(candidate);
                assertCurrentRuntimeProjection(candidate);
            }
            transitionCandidateStatus(candidate.getId(), previousStatus, "REJECTED", "REVOKED",
                trimToNull(request == null ? null : request.getNote()));
            if ("ACTIVE".equals(previousStatus)) {
                disableRuntimeSkill(candidate, true);
            }
            setRevocationMetadata(candidate.getId(), currentAdminId());
            SkillCandidateVO rejected = find(candidate.getId());
            auditSkill(rejected, null, "REJECTED", previousStatus, "REVOKED", Map.of());
            return rejected;
        });
    }

    public SkillCandidateVO publish(Long id) {
        requireAdmin();
        requireGovernedCandidateSchema();
        requireGovernedRuntimeSchema();
        return inTransaction(() -> {
            SkillCandidateVO candidate = lockAndFind(id, true);
            assertPublishable(candidate);
            List<SkillCandidateVO> existingActive = findActiveCandidates(candidate.getSkillId(), candidate.getId());
            if (existingActive.size() > 1) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "multiple active skill candidates detected");
            }
            assertRuntimeProjectionMatches(candidate.getSkillId(), existingActive);
            SkillCandidateVO previous = existingActive.isEmpty() ? null : existingActive.get(0);
            for (SkillCandidateVO existing : existingActive) {
                transitionCandidateStatus(existing.getId(), "ACTIVE", "ROLLED_BACK", "REVOKED", null);
                SkillCandidateVO replaced = find(existing.getId());
                auditSkill(replaced, candidate.getId(), "REPLACED", "ACTIVE", "REVOKED",
                    Map.of("reason", "newer version activated"));
            }
            transitionCandidateStatus(candidate.getId(), "APPROVED", "PUBLISHED", "ACTIVE", null);
            SkillCandidateVO active = find(candidate.getId());
            upsertRuntimeSkill(active, previous == null ? null : previous.getVersion());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("runtimeStatus", "ACTIVE");
            if (previous != null) {
                details.put("previousCandidateId", previous.getId());
                details.put("previousVersion", previous.getVersion());
            }
            auditSkill(active, previous == null ? null : previous.getId(), "ACTIVATED",
                candidate.getLifecycleStatus(), "ACTIVE", details);
            return active;
        });
    }

    public SkillCandidateVO disable(Long id) {
        requireAdmin();
        requireGovernedCandidateSchema();
        requireGovernedRuntimeSchema();
        return inTransaction(() -> {
            SkillCandidateVO candidate = lockAndFind(id, true);
            requireLifecycle(candidate, "ACTIVE", "only the current active candidate can be disabled");
            assertOnlyActiveCandidate(candidate);
            assertCurrentRuntimeProjection(candidate);
            transitionCandidateStatus(candidate.getId(), "ACTIVE", "DISABLED", "REVOKED", null);
            disableRuntimeSkill(candidate, true);
            setRevocationMetadata(candidate.getId(), currentAdminId());
            SkillCandidateVO revoked = find(candidate.getId());
            auditSkill(revoked, null, "REVOKED", "ACTIVE", "REVOKED", Map.of());
            return revoked;
        });
    }

    public SkillCandidateVO rollback(Long id) {
        requireAdmin();
        requireGovernedCandidateSchema();
        requireGovernedRuntimeSchema();
        requireSkillAuditTable();
        return inTransaction(() -> {
            SkillCandidateVO current = lockAndFind(id, true);
            if (!"ACTIVE".equals(current.getLifecycleStatus()) && !"REVOKED".equals(current.getLifecycleStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "only active or revoked skill can be rolled back");
            }
            assertLatestActivatedCandidate(current);
            List<SkillCandidateVO> activeCandidates = findActiveCandidates(current.getSkillId(), -1L);
            if ("ACTIVE".equals(current.getLifecycleStatus())) {
                assertOnlyActiveCandidate(current);
                assertCurrentRuntimeProjection(current);
            } else if (!activeCandidates.isEmpty() || activeRuntimeCandidateId(current.getSkillId()) != null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "revoked candidate is not the current rollback source");
            }
            SkillCandidateVO previous = findRollbackTarget(current);
            requireLifecycle(previous, "REVOKED", "rollback target must be a previously revoked active version");
            assertActivatableContract(previous);
            if ("ACTIVE".equals(current.getLifecycleStatus())) {
                transitionCandidateStatus(current.getId(), "ACTIVE", "ROLLED_BACK", "REVOKED", null);
            }
            transitionCandidateStatus(previous.getId(), "REVOKED", "PUBLISHED", "ACTIVE", null);
            SkillCandidateVO restored = find(previous.getId());
            upsertRuntimeSkill(restored, current.getVersion());
            auditSkill(find(current.getId()), restored.getId(), "REPLACED", current.getLifecycleStatus(), "REVOKED",
                Map.of("reason", "rollback"));
            auditSkill(restored, current.getId(), "ROLLED_BACK_TO", previous.getLifecycleStatus(), "ACTIVE", Map.of());
            return restored;
        });
    }

    private SkillCandidateVO find(Long id) {
        List<SkillCandidateVO> candidates = jdbcTemplate.query(selectCandidateColumns() + " from ai_skill_candidate where id = ?", mapper(), id);
        if (candidates.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "skill candidate not found");
        }
        return candidates.get(0);
    }

    private SkillCandidateVO lockAndFind(Long id, boolean lockRuntime) {
        SkillCandidateVO initial = find(id);
        jdbcTemplate.queryForList(
            "select id from ai_skill_candidate where skill_id = ? order by id for update",
            Long.class,
            initial.getSkillId()
        );
        if (lockRuntime && hasTable("ai_runtime_skill")) {
            jdbcTemplate.queryForList(
                "select id from ai_runtime_skill where skill_id = ? for update",
                Long.class,
                initial.getSkillId()
            );
        }
        return find(id);
    }

    private void requireLifecycle(SkillCandidateVO candidate, String expected, String message) {
        if (candidate == null || !expected.equals(candidate.getLifecycleStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
    }

    private void assertOnlyActiveCandidate(SkillCandidateVO expected) {
        List<SkillCandidateVO> active = findActiveCandidates(expected.getSkillId(), -1L);
        if (active.size() != 1 || !Objects.equals(active.get(0).getId(), expected.getId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "candidate is not the only current active version");
        }
    }

    private void assertRuntimeProjectionMatches(String skillId, List<SkillCandidateVO> activeCandidates) {
        Long runtimeCandidateId = activeRuntimeCandidateId(skillId);
        if (activeCandidates.isEmpty()) {
            if (runtimeCandidateId != null) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "active runtime skill has no active candidate");
            }
            return;
        }
        if (!Objects.equals(runtimeCandidateId, activeCandidates.get(0).getId())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "runtime skill projection does not match active candidate");
        }
    }

    private void assertCurrentRuntimeProjection(SkillCandidateVO candidate) {
        Long runtimeCandidateId = activeRuntimeCandidateId(candidate.getSkillId());
        if (!Objects.equals(runtimeCandidateId, candidate.getId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "candidate is not the current active runtime skill");
        }
    }

    private Long activeRuntimeCandidateId(String skillId) {
        if (skillId == null || !hasTable("ai_runtime_skill")) {
            return null;
        }
        List<Long> candidateIds = jdbcTemplate.query("""
                select candidate_id from ai_runtime_skill
                where skill_id = ? and status = 'ACTIVE'
                order by id desc
                """, (rs, rowNum) -> {
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        }, skillId);
        if (candidateIds.size() > 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "multiple active runtime skill rows detected");
        }
        if (candidateIds.size() == 1 && candidateIds.get(0) == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "active runtime skill is missing candidate_id");
        }
        return candidateIds.isEmpty() ? null : candidateIds.get(0);
    }

    private void assertLatestActivatedCandidate(SkillCandidateVO candidate) {
        List<Long> latest = jdbcTemplate.queryForList("""
                select candidate_id
                from ai_skill_lifecycle_audit
                where skill_id = ? and event_type in ('ACTIVATED', 'ROLLED_BACK_TO')
                order by id desc
                limit 1
                """, Long.class, candidate.getSkillId());
        if (latest.isEmpty() || !Objects.equals(latest.get(0), candidate.getId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "candidate is not the latest actually activated version");
        }
    }

    private List<SkillCandidateVO> findActiveCandidates(String skillId, Long excludingId) {
        return jdbcTemplate.query(selectCandidateColumns() + " from ai_skill_candidate where skill_id = ? and lifecycle_status = 'ACTIVE' and id <> ?",
            mapper(), skillId, excludingId);
    }

    private SkillCandidateVO findRollbackTarget(SkillCandidateVO current) {
        List<Long> previousIds = jdbcTemplate.queryForList("""
                select candidate_id
                from ai_skill_lifecycle_audit
                where skill_id = ? and related_candidate_id = ? and event_type = 'REPLACED'
                order by id desc
                limit 1
                """, Long.class, current.getSkillId(), current.getId());
        if (previousIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "no previous skill version available for rollback");
        }
        return find(previousIds.get(0));
    }

    private void transitionCandidateStatus(Long id, String expectedLifecycleStatus, String legacyStatus,
                                           String lifecycleStatus, String reviewNote) {
        StringBuilder sql = new StringBuilder("update ai_skill_candidate set status = ?, updated_at = current_timestamp");
        List<Object> args = new ArrayList<>();
        args.add(legacyStatus);
        sql.append(", lifecycle_status = ?");
        args.add(lifecycleStatus);
        if (reviewNote != null) {
            sql.append(", review_note = ?");
            args.add(reviewNote);
        }
        sql.append(" where id = ? and lifecycle_status = ?");
        args.add(id);
        args.add(expectedLifecycleStatus);
        int updated = jdbcTemplate.update(sql.toString(), args.toArray());
        if (updated != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill lifecycle transition conflict");
        }
    }

    private void setApprovalMetadata(Long id, Long adminId) {
        if (!hasColumn("ai_skill_candidate", "approved_by") || !hasColumn("ai_skill_candidate", "approved_at")) {
            return;
        }
        jdbcTemplate.update("update ai_skill_candidate set approved_by = ?, approved_at = current_timestamp where id = ?", adminId, id);
    }

    private void setRevocationMetadata(Long id, Long adminId) {
        if (!hasColumn("ai_skill_candidate", "revoked_by") || !hasColumn("ai_skill_candidate", "revoked_at")) {
            return;
        }
        jdbcTemplate.update("update ai_skill_candidate set revoked_by = ?, revoked_at = current_timestamp where id = ?", adminId, id);
    }

    private void upsertRuntimeSkill(SkillCandidateVO candidate, String rollbackVersion) {
        SkillContract contract = resolveCandidateContract(candidate);
        validateCandidateContract(candidate);
        assertCandidateHashMatches(candidate, contract.contentHash());
        String content = candidate.getContent();
        Map<String, Object> eval = parseEvalResultOrEmpty(candidate.getEvalResultJson());
        List<String> intents = contract.intents();
        List<String> triggers = readStringList(eval, "triggers");
        List<String> requiredEvidence = readStringList(eval, "requiredEvidence", "required_evidence");
        String promptFragment = firstString(eval, "promptFragment", "prompt_fragment");
        String guardrailsJson = writeJson(firstPresent(eval, "guardrails", "guardrailsJson"));
        String negativeRulesJson = writeJson(firstPresent(eval, "negativeRules", "negative_rules"));
        String outputContractJson = writeJson(firstPresent(eval, "outputContract", "output_contract"));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("candidate_id", candidate.getId());
        fields.put("version", contract.version());
        fields.put("title", candidate.getTitle());
        fields.put("description", candidate.getDescription());
        fields.put("content", content);
        fields.put("status", "ACTIVE");
        fields.put("intents_json", writeJson(intents));
        fields.put("triggers_json", writeJson(triggers));
        fields.put("requested_capabilities_json", writeJson(contract.requestedCapabilities()));
        fields.put("skill_metadata_json", candidate.getSkillMetadataJson());
        fields.put("required_evidence_json", writeJson(requiredEvidence));
        fields.put("prompt_fragment", promptFragment);
        fields.put("guardrails_json", guardrailsJson);
        fields.put("negative_rules_json", negativeRulesJson);
        fields.put("output_contract_json", outputContractJson);
        fields.put("eval_result_json", candidate.getEvalResultJson());
        fields.put("source_trace_id", candidate.getSourceTraceId());
        if (runtimeContentHashColumnAvailable) {
            fields.put("content_hash", contract.contentHash());
        }
        if (runtimeInputSchemaColumnAvailable) {
            fields.put("input_schema_json", contract.inputSchemaJson());
        }
        if (runtimeOutputSchemaColumnAvailable) {
            fields.put("output_schema_json", contract.outputSchemaJson());
        }
        if (runtimeRolloutPolicyColumnAvailable) {
            fields.put("rollout_policy_json", contract.rolloutPolicyJson());
        }
        if (runtimeActivatedByColumnAvailable) fields.put("activated_by", currentAdminId());
        if (runtimeRollbackVersionColumnAvailable) fields.put("rollback_version", rollbackVersion);
        List<String> columns = new ArrayList<>(List.of("skill_id"));
        List<Object> values = new ArrayList<>(List.of(candidate.getSkillId()));
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            columns.add(entry.getKey());
            values.add(entry.getValue());
        }
        String updateSql = "update ai_runtime_skill set " + String.join(", ", fields.keySet())
            .replace(", ", " = ?, ") + " = ?, published_at = current_timestamp, disabled_at = null, updated_at = current_timestamp"
            + " where skill_id = ?";
        List<Object> updateArgs = new ArrayList<>(fields.values());
        updateArgs.add(candidate.getSkillId());
        int updated = jdbcTemplate.update(updateSql, updateArgs.toArray());
        if (updated == 0) {
            try {
                jdbcTemplate.update("insert into ai_runtime_skill(" + String.join(", ", columns) + ") values("
                    + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")", values.toArray());
            } catch (DuplicateKeyException duplicate) {
                jdbcTemplate.update(updateSql, updateArgs.toArray());
            }
        }
        if (runtimeActivatedAtColumnAvailable) {
            jdbcTemplate.update("update ai_runtime_skill set activated_at = current_timestamp where skill_id = ?", candidate.getSkillId());
        }
    }

    private void disableRuntimeSkill(SkillCandidateVO candidate, boolean requireCurrent) {
        if (candidate == null) return;
        int updated = jdbcTemplate.update("""
                update ai_runtime_skill
                set status = 'DISABLED', disabled_at = current_timestamp, updated_at = current_timestamp
                where skill_id = ? and candidate_id = ? and status = 'ACTIVE'
                """, candidate.getSkillId(), candidate.getId());
        if (requireCurrent && updated != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "active runtime skill projection was not disabled");
        }
    }

    private SkillImport resolveSkillImport(SkillCandidateCreateRequest request) {
        String submittedContent = rawContent(request == null ? null : request.getContent());
        if (submittedContent == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill content is required");
        }
        if (skillMarkdownParser.isStandardSkill(submittedContent)) {
            try {
                AgentSkillMarkdownParser.ParsedSkill parsed = skillMarkdownParser.parse(submittedContent);
                String submittedSkillId = trimToNull(request == null ? null : request.getSkillId());
                if (submittedSkillId != null && !submittedSkillId.equals(parsed.name())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "skillId conflicts with SKILL.md name");
                }
                String submittedTitle = trimToNull(request == null ? null : request.getTitle());
                if (submittedTitle != null && !submittedTitle.equals(parsed.name())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "title conflicts with SKILL.md name");
                }
                List<String> submittedCapabilities = request == null
                    ? List.of()
                    : normalizeStringList(request.getRequestedCapabilities());
                if (!submittedCapabilities.isEmpty()
                    && !submittedCapabilities.equals(parsed.requestedCapabilities())) {
                    throw new BusinessException(
                        ResultCode.BAD_REQUEST,
                        "requested capabilities conflict with SKILL.md allowed-tools"
                    );
                }
                return new SkillImport(
                    parsed.name(),
                    parsed.name(),
                    parsed.description(),
                    parsed.instructions(),
                    parsed.requestedCapabilities(),
                    writeJson(parsed.metadata()),
                    true
                );
            } catch (BusinessException exception) {
                throw exception;
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ResultCode.BAD_REQUEST, exception.getMessage());
            }
        }

        String skillId = requireText(request == null ? null : request.getSkillId(), "skillId is required");
        String title = requireText(request == null ? null : request.getTitle(), "title is required");
        List<String> requestedCapabilities = request == null
            ? List.of()
            : normalizeStringList(request.getRequestedCapabilities());
        return new SkillImport(
            skillId,
            title,
            title,
            submittedContent,
            requestedCapabilities,
            writeJson(Map.of("legacyFormat", true)),
            false
        );
    }

    private SkillContract resolveCreateContract(
        SkillCandidateCreateRequest request,
        Map<String, Object> eval,
        String content,
        SkillImport skillImport
    ) {
        String contentHash = sha256Hex(content == null ? "" : content);
        String version = firstNonBlank(
            trimToNull(request == null ? null : request.getVersion()),
            firstString(eval, "version", "skillVersion", "releaseVersion"),
            "draft-" + contentHash.substring(0, 12)
        );
        List<String> requestedCapabilities = skillImport.standardFormat()
            ? skillImport.requestedCapabilities()
            : firstNonEmptyList(
                skillImport.requestedCapabilities(),
                readStringList(eval, "requestedCapabilities", "requested_capabilities")
            );
        String inputSchema = firstNonBlank(
            trimToNull(request == null ? null : request.getInputSchemaJson()),
            jsonValue(firstPresent(eval, "inputSchema", "input_schema"))
        );
        String outputSchema = firstNonBlank(
            trimToNull(request == null ? null : request.getOutputSchemaJson()),
            jsonValue(firstPresent(eval, "outputSchema", "output_schema"))
        );
        String rolloutPolicy = firstNonBlank(
            trimToNull(request == null ? null : request.getRolloutPolicyJson()),
            jsonValue(firstPresent(eval, "rolloutPolicy", "rollout_policy"))
        );
        return new SkillContract(
            version,
            contentHash,
            readStringList(eval, "intents"),
            requestedCapabilities,
            inputSchema,
            outputSchema,
            rolloutPolicy
        );
    }

    private List<String> firstNonEmptyList(List<String> first, List<String> second) {
        return first == null || first.isEmpty() ? List.copyOf(second) : List.copyOf(first);
    }

    private SkillContract resolveCandidateContract(SkillCandidateVO candidate) {
        Map<String, Object> eval = parseEvalResultOrEmpty(candidate.getEvalResultJson());
        return new SkillContract(
            candidate.getVersion(),
            candidate.getContentHash(),
            readStringList(eval, "intents"),
            parseStringListStrict(candidate.getRequestedCapabilitiesJson(), "requested capabilities"),
            candidate.getInputSchemaJson(),
            candidate.getOutputSchemaJson(),
            candidate.getRolloutPolicyJson()
        );
    }

    private void validateCandidateContract(SkillCandidateVO candidate) {
        Map<String, Object> eval = parseEvalResultOrEmpty(candidate.getEvalResultJson());
        validateSkillContract(candidate.getSkillId(), resolveCandidateContract(candidate), candidate.getContent(), eval);
    }

    private void validateSkillContract(String skillId, SkillContract contract, String content, Map<String, Object> eval) {
        validateSkillId(skillId);
        String version = trimToNull(contract.version());
        if (version == null || version.length() > 80) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill version is required");
        }
        if (content == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill content is required");
        }
        String contentHash = trimToNull(contract.contentHash());
        if (contentHash == null || !SHA256_PATTERN.matcher(contentHash).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "persisted skill content hash is required");
        }
        if (!contentHash.equals(sha256Hex(content))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill content hash does not match exact UTF-8 content");
        }
        for (String intent : contract.intents()) {
            if (!ALLOWED_INTENTS.contains(intent)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill intent is not registered: " + intent);
            }
        }
        validatePromptText(content);
        validatePromptMetadata(eval);
        validateRuntimeMetadataShapes(eval);
        validateJsonSchema(contract.inputSchemaJson(), "input schema");
        validateJsonSchema(contract.outputSchemaJson(), "output schema");
        validateJsonObject(contract.rolloutPolicyJson(), "rollout policy");
        for (String capability : contract.requestedCapabilities()) {
            if (!ALLOWED_CAPABILITIES.contains(capability)) {
                throw new BusinessException(
                    ResultCode.BAD_REQUEST,
                    "skill requested capability is not registered: " + capability
                );
            }
        }
    }

    private void validateSkillId(String skillId) {
        if (skillId == null || !SKILL_ID_PATTERN.matcher(skillId).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skillId does not match the governed runtime contract");
        }
    }

    private void validatePromptText(String content) {
        if (content == null) return;
        for (Pattern pattern : PROMPT_INJECTION_PATTERNS) {
            if (pattern.matcher(content).find()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill prompt metadata contains prompt injection instruction");
            }
        }
    }

    private void validatePromptMetadata(Map<String, Object> eval) {
        validatePromptText(jsonValue(firstPresent(eval, "promptFragment", "prompt_fragment")));
        for (String key : PROMPT_METADATA_KEYS) {
            validatePromptText(jsonValue(firstPresent(eval, key)));
        }
    }

    private void validateRuntimeMetadataShapes(Map<String, Object> eval) {
        requireStringListMetadata(eval, false, "intents");
        requireStringListMetadata(eval, false, "requestedCapabilities", "requested_capabilities");
        requireStringListMetadata(eval, false, "triggers");
        requireStringListMetadata(eval, false, "appliesTo", "applies_to");
        requireStringListMetadata(eval, false, "requiredEvidence", "required_evidence");
        requireStringListMetadata(eval, false, "guardrails");
        requireStringListMetadata(eval, false, "negativeRules", "negative_rules");
        requireStringListMetadata(eval, false, "qualityChecklist", "quality_checklist");
        requireStringListMetadata(eval, false, "examples");
        Object outputContract = firstPresent(eval, "outputContract", "output_contract");
        if (outputContract != null && !(outputContract instanceof String) && !(outputContract instanceof Map<?, ?>)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill output contract must be text or a JSON object");
        }
        String outputContractText = jsonValue(outputContract);
        if (outputContractText != null && outputContractText.length() > MAX_RUNTIME_SCALAR_PROMPT_CHARS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill output contract exceeds the runtime prompt limit");
        }
    }

    private void requireStringListMetadata(Map<String, Object> eval, boolean required, String... keys) {
        Object value = firstPresent(eval, keys);
        if (value == null) {
            if (required) throw new BusinessException(ResultCode.BAD_REQUEST, "skill intents are required");
            return;
        }
        boolean scalarAllowed = "guardrails".equals(keys[0]) || "negativeRules".equals(keys[0]);
        List<?> values;
        if (value instanceof String text && scalarAllowed) {
            values = List.of(text);
        } else if (value instanceof List<?> list) {
            values = list;
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill metadata " + keys[0] + " must be a non-empty JSON array");
        }
        if (required && values.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill metadata " + keys[0] + " must be a non-empty JSON array");
        }
        int maxItems = "examples".equals(keys[0]) ? MAX_PROMPT_EXAMPLES : MAX_PROMPT_METADATA_ITEMS;
        if (values.size() > maxItems) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill metadata " + keys[0] + " has too many values");
        }
        int totalChars = 0;
        for (Object item : values) {
            if (!(item instanceof String text) || trimToNull(text) == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill metadata " + keys[0] + " must contain text values");
            }
            String normalized = text.trim();
            if (normalized.length() > MAX_PROMPT_METADATA_ITEM_CHARS) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill metadata " + keys[0] + " contains an oversized value");
            }
            if (scalarAllowed && totalChars > 0) totalChars++;
            totalChars += normalized.length();
        }
        int maxTotalChars = scalarAllowed ? MAX_RUNTIME_SCALAR_PROMPT_CHARS : MAX_PROMPT_METADATA_TOTAL_CHARS;
        if (totalChars > maxTotalChars) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill metadata " + keys[0] + " exceeds the runtime prompt limit");
        }
    }

    private void validateJsonObject(String json, String label) {
        String normalized = trimToNull(json);
        if (normalized == null) return;
        Map<String, Object> value = parseJsonObjectStrict(normalized, label);
        validateJsonComplexity(value, label, 0, new int[]{0}, false);
    }

    private void validateJsonSchema(String json, String label) {
        String normalized = trimToNull(json);
        if (normalized == null) return;
        if (normalized.length() > MAX_SCHEMA_CHARS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " exceeds the size limit");
        }
        Map<String, Object> schema = parseJsonObjectStrict(normalized, label);
        if (!"object".equals(schema.get("type"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " root type must be object");
        }
        validateJsonComplexity(schema, label, 0, new int[]{0}, true);
    }

    private Map<String, Object> parseJsonObjectStrict(String json, String label) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " must be a JSON object");
        }
    }

    private void validateJsonComplexity(Object value, String label, int depth, int[] nodes, boolean schema) {
        if (depth > MAX_SCHEMA_DEPTH || ++nodes[0] > MAX_SCHEMA_NODES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " is too complex");
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_SCHEMA_MAP_ENTRIES) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " has too many object entries");
            }
            if (schema) validateSchemaKeywords(map, label);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateJsonComplexity(entry.getValue(), label, depth + 1, nodes, schema);
            }
        } else if (value instanceof List<?> list) {
            if (list.size() > MAX_SCHEMA_MAP_ENTRIES) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " has too many array entries");
            }
            for (Object item : list) validateJsonComplexity(item, label, depth + 1, nodes, schema);
        } else if (value instanceof String text && text.length() > 4096) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " contains an oversized string");
        }
    }

    private void validateSchemaKeywords(Map<?, ?> map, String label) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String refKey = entry.getKey() instanceof String text ? text : null;
            if (refKey != null && refKey.startsWith("$") && refKey.toLowerCase(Locale.ROOT).endsWith("ref")) {
                Object ref = entry.getValue();
                if (!(ref instanceof String text) || !text.startsWith("#")) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " cannot use remote references");
                }
            }
        }
        Object type = map.get("type");
        if (type instanceof String text && !JSON_SCHEMA_TYPES.contains(text)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " contains an invalid JSON Schema type");
        }
        if (type instanceof List<?> values && (values.isEmpty() || values.stream().anyMatch(item -> !(item instanceof String text)
            || !JSON_SCHEMA_TYPES.contains(text)))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " contains an invalid JSON Schema type");
        }
        if (type != null && !(type instanceof String) && !(type instanceof List<?>)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " type must be text or an array");
        }
        for (String objectKeyword : List.of("properties", "$defs", "definitions", "patternProperties", "dependentSchemas")) {
            Object nested = map.get(objectKeyword);
            if (nested != null && !(nested instanceof Map<?, ?>)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " keyword " + objectKeyword + " must be an object");
            }
        }
        Object required = map.get("required");
        if (required != null && (!(required instanceof List<?> values)
            || values.stream().anyMatch(item -> !(item instanceof String text) || text.isEmpty()))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " required must be a non-empty string array");
        }
        for (String schemaKeyword : List.of(
            "additionalProperties", "unevaluatedProperties", "unevaluatedItems", "items", "contains",
            "propertyNames", "not", "if", "then", "else"
        )) {
            Object nested = map.get(schemaKeyword);
            if (nested != null && !(nested instanceof Map<?, ?>) && !(nested instanceof Boolean)
                && !("items".equals(schemaKeyword) && nested instanceof List<?>)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " keyword " + schemaKeyword + " must contain a schema");
            }
        }
        for (String schemaArrayKeyword : List.of("allOf", "anyOf", "oneOf", "prefixItems")) {
            Object nested = map.get(schemaArrayKeyword);
            if (nested != null && !(nested instanceof List<?>)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " keyword " + schemaArrayKeyword + " must be an array");
            }
        }
    }

    private void assertCandidateHashMatches(SkillCandidateVO candidate, String computedHash) {
        String stored = trimToNull(candidate.getContentHash());
        if (stored == null || !SHA256_PATTERN.matcher(stored).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "persisted skill content hash is required");
        }
        if (!stored.equals(computedHash)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill content hash does not match exact UTF-8 content");
        }
    }

    private boolean runtimeContentIsTrusted(RuntimeSkillVO runtime) {
        try {
            if (runtime == null || !"ACTIVE".equals(runtime.getStatus())) return false;
            if (!runtimeProjectionMatchesCandidate(runtime)) return false;
            validateSkillId(runtime.getSkillId());
            if (trimToNull(runtime.getVersion()) == null || runtime.getContent() == null) return false;
            String persistedHash = trimToNull(runtime.getContentHash());
            if (persistedHash == null || !SHA256_PATTERN.matcher(persistedHash).matches()
                || !persistedHash.equals(sha256Hex(runtime.getContent()))) return false;
            if (runtime.getIntents() == null || runtime.getIntents().isEmpty()
                || runtime.getIntents().stream().anyMatch(intent -> !ALLOWED_INTENTS.contains(intent))) return false;
            if (runtime.getRequestedCapabilities() == null
                || runtime.getRequestedCapabilities().stream().anyMatch(
                    capability -> !ALLOWED_CAPABILITIES.contains(capability)
                )) return false;
            validatePromptText(runtime.getContent());
            validatePromptText(writeJson(runtime.getAppliesTo()));
            validatePromptText(writeJson(runtime.getRequiredEvidence()));
            validatePromptText(writeJson(runtime.getQualityChecklist()));
            validatePromptText(writeJson(runtime.getExamples()));
            validatePromptText(runtime.getGuardrails());
            validatePromptText(runtime.getNegativeRules());
            validatePromptText(runtime.getOutputContract());
            if (runtime.getInputSchema() != null && !runtime.getInputSchema().isEmpty()) {
                validateJsonSchema(writeJson(runtime.getInputSchema()), "input schema");
            }
            if (runtime.getOutputSchema() != null && !runtime.getOutputSchema().isEmpty()) {
                validateJsonSchema(writeJson(runtime.getOutputSchema()), "output schema");
            }
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    private boolean runtimeProjectionMatchesCandidate(RuntimeSkillVO runtime) {
        if (runtime.getCandidateId() == null) return false;
        SkillCandidateVO candidate = find(runtime.getCandidateId());
        if (!"ACTIVE".equals(candidate.getLifecycleStatus())
            || !Objects.equals(candidate.getSkillId(), runtime.getSkillId())
            || !Objects.equals(candidate.getVersion(), runtime.getVersion())
            || !Objects.equals(candidate.getTitle(), runtime.getTitle())
            || !Objects.equals(candidate.getDescription(), runtime.getDescription())
            || !Objects.equals(candidate.getContent(), runtime.getContent())
            || !Objects.equals(candidate.getContentHash(), runtime.getContentHash())) {
            return false;
        }
        SkillContract contract = resolveCandidateContract(candidate);
        Map<String, Object> eval = parseEvalResultOrEmpty(candidate.getEvalResultJson());
        return Objects.equals(contract.intents(), runtime.getIntents())
            && Objects.equals(contract.requestedCapabilities(), runtime.getRequestedCapabilities())
            && Objects.equals(parseJsonObject(candidate.getSkillMetadataJson()), runtime.getSkillMetadata())
            && Objects.equals(readStringList(eval, "triggers"), runtime.getTriggers())
            && Objects.equals(readStringList(eval, "appliesTo", "applies_to"), runtime.getAppliesTo())
            && Objects.equals(readStringList(eval, "requiredEvidence", "required_evidence"), runtime.getRequiredEvidence())
            && Objects.equals(readStringList(eval, "qualityChecklist", "quality_checklist"), runtime.getQualityChecklist())
            && Objects.equals(readStringList(eval, "examples"), runtime.getExamples())
            && Objects.equals(firstString(eval, "promptFragment", "prompt_fragment"), runtime.getPromptFragment())
            && Objects.equals(runtimePromptText(writeJson(firstPresent(eval, "guardrails", "guardrailsJson"))), runtime.getGuardrails())
            && Objects.equals(runtimePromptText(writeJson(firstPresent(eval, "negativeRules", "negative_rules"))), runtime.getNegativeRules())
            && Objects.equals(runtimePromptText(writeJson(firstPresent(eval, "outputContract", "output_contract"))), runtime.getOutputContract())
            && Objects.equals(parseJsonObject(contract.inputSchemaJson()), runtime.getInputSchema())
            && Objects.equals(parseJsonObject(contract.outputSchemaJson()), runtime.getOutputSchema())
            && Objects.equals(parseJsonObject(contract.rolloutPolicyJson()), runtime.getRolloutPolicy());
    }

    private void assertPublishable(SkillCandidateVO candidate) {
        requireLifecycle(candidate, "APPROVED", "candidate must be approved before publish");
        assertActivatableContract(candidate);
    }

    private void assertActivatableContract(SkillCandidateVO candidate) {
        if ("FAILED".equalsIgnoreCase(candidate.getEvalStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "failed eval candidate cannot be published");
        }
        if (!hasStructuredEvalResult(candidate)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "structured eval result is required before publish");
        }
        assertStructuredEvalPassed(candidate);
        validateCandidateContract(candidate);
        assertCandidateHashMatches(candidate, sha256Hex(candidate.getContent()));
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
        Object metricsObject = result.containsKey("metrics") ? result.get("metrics") : result;
        if (!(metricsObject instanceof Map<?, ?> metrics)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill eval metrics must be a JSON object");
        }
        assertMetricAtLeast(metrics, "requiredToolPassRate", REQUIRED_TOOL_PASS_RATE_THRESHOLD);
        assertMetricAtLeast(metrics, "evidencePassRate", EVIDENCE_PASS_RATE_THRESHOLD);
        assertMetricAtLeast(metrics, "faithfulnessPassRate", FAITHFULNESS_PASS_RATE_THRESHOLD);
    }

    private boolean hasMetricColumns(SkillCandidateVO candidate) {
        return candidate.getRequiredToolPassRate() != null || candidate.getEvidencePassRate() != null
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

    private RowMapper<SkillCandidateVO> mapper() {
        return (rs, rowNum) -> {
            SkillCandidateVO vo = new SkillCandidateVO();
            vo.setId(rs.getLong("id"));
            vo.setSkillId(rs.getString("skill_id"));
            vo.setTitle(rs.getString("title"));
            vo.setDescription(readStringIfAvailable(rs, "description", descriptionColumnAvailable));
            vo.setContent(readStringIfAvailable(rs, "content", contentColumnAvailable));
            String legacyStatus = rs.getString("status");
            vo.setStatus(legacyStatus);
            vo.setLegacyStatus(legacyStatus);
            vo.setLifecycleStatus(lifecycleStatusColumnAvailable
                ? firstNonBlank(rs.getString("lifecycle_status"), lifecycleFromLegacy(legacyStatus))
                : lifecycleFromLegacy(legacyStatus));
            vo.setEvalStatus(rs.getString("eval_status"));
            vo.setReviewNote(rs.getString("review_note"));
            vo.setSourceTraceId(readStringIfAvailable(rs, "source_trace_id", sourceTraceIdColumnAvailable));
            vo.setEvalResultJson(readStringIfAvailable(rs, "eval_result_json", evalResultJsonColumnAvailable));
            vo.setRequiredToolPassRate(readNullableDouble(rs, "required_tool_pass_rate", requiredToolPassRateColumnAvailable));
            vo.setEvidencePassRate(readNullableDouble(rs, "evidence_pass_rate", evidencePassRateColumnAvailable));
            vo.setFaithfulnessPassRate(readNullableDouble(rs, "faithfulness_pass_rate", faithfulnessPassRateColumnAvailable));
            vo.setVersion(readStringIfAvailable(rs, "version", versionColumnAvailable));
            vo.setContentHash(readStringIfAvailable(rs, "content_hash", contentHashColumnAvailable));
            vo.setInputSchemaJson(readStringIfAvailable(rs, "input_schema_json", inputSchemaColumnAvailable));
            vo.setOutputSchemaJson(readStringIfAvailable(rs, "output_schema_json", outputSchemaColumnAvailable));
            vo.setRequestedCapabilitiesJson(readStringIfAvailable(
                rs,
                "requested_capabilities_json",
                requestedCapabilitiesColumnAvailable
            ));
            vo.setSkillMetadataJson(readStringIfAvailable(rs, "skill_metadata_json", skillMetadataColumnAvailable));
            vo.setRolloutPolicyJson(readStringIfAvailable(rs, "rollout_policy_json", rolloutPolicyColumnAvailable));
            return vo;
        };
    }

    private String selectCandidateColumns() {
        String columns = "select id, skill_id, title, status, eval_status, review_note";
        if (contentColumnAvailable) columns += ", content";
        if (sourceTraceIdColumnAvailable) columns += ", source_trace_id";
        if (evalResultJsonColumnAvailable) columns += ", eval_result_json";
        if (requiredToolPassRateColumnAvailable) columns += ", required_tool_pass_rate";
        if (evidencePassRateColumnAvailable) columns += ", evidence_pass_rate";
        if (faithfulnessPassRateColumnAvailable) columns += ", faithfulness_pass_rate";
        if (lifecycleStatusColumnAvailable) columns += ", lifecycle_status";
        if (versionColumnAvailable) columns += ", version";
        if (contentHashColumnAvailable) columns += ", content_hash";
        if (inputSchemaColumnAvailable) columns += ", input_schema_json";
        if (outputSchemaColumnAvailable) columns += ", output_schema_json";
        if (descriptionColumnAvailable) columns += ", description";
        if (requestedCapabilitiesColumnAvailable) columns += ", requested_capabilities_json";
        if (skillMetadataColumnAvailable) columns += ", skill_metadata_json";
        if (rolloutPolicyColumnAvailable) columns += ", rollout_policy_json";
        return columns;
    }

    private String runtimeSelectColumns() {
        String columns = "select candidate_id, skill_id, version, title, content, status, intents_json, triggers_json, "
            + "description, requested_capabilities_json, skill_metadata_json, required_evidence_json, "
            + "prompt_fragment, guardrails_json, negative_rules_json, "
            + "output_contract_json, eval_result_json, source_trace_id";
        if (runtimeContentHashColumnAvailable) columns += ", content_hash";
        if (runtimeInputSchemaColumnAvailable) columns += ", input_schema_json";
        if (runtimeOutputSchemaColumnAvailable) columns += ", output_schema_json";
        if (runtimeRolloutPolicyColumnAvailable) columns += ", rollout_policy_json";
        return columns;
    }

    private RuntimeSkillVO mapRuntimeSkill(ResultSet rs) throws SQLException {
        RuntimeSkillVO vo = new RuntimeSkillVO();
        long candidateId = rs.getLong("candidate_id");
        vo.setCandidateId(rs.wasNull() ? null : candidateId);
        vo.setSkillId(rs.getString("skill_id"));
        vo.setVersion(rs.getString("version"));
        vo.setTitle(rs.getString("title"));
        vo.setDescription(rs.getString("description"));
        vo.setContent(rs.getString("content"));
        vo.setStatus(rs.getString("status"));
        vo.setContentHash(readStringIfAvailable(rs, "content_hash", runtimeContentHashColumnAvailable));
        vo.setSourceTraceId(rs.getString("source_trace_id"));
        Map<String, Object> eval = parseEvalResultOrEmpty(rs.getString("eval_result_json"));
        vo.setIntents(parseStringListStrict(rs.getString("intents_json"), "runtime intents"));
        vo.setTriggers(parseStringListStrict(rs.getString("triggers_json"), "runtime triggers"));
        vo.setAppliesTo(readStringList(eval, "appliesTo", "applies_to"));
        vo.setRequestedCapabilities(parseStringListStrict(
            rs.getString("requested_capabilities_json"),
            "runtime requested capabilities"
        ));
        vo.setSkillMetadata(parseJsonObject(rs.getString("skill_metadata_json")));
        vo.setRequiredEvidence(parseStringListStrict(rs.getString("required_evidence_json"), "runtime required evidence"));
        vo.setQualityChecklist(readStringList(eval, "qualityChecklist", "quality_checklist"));
        vo.setExamples(readStringList(eval, "examples"));
        vo.setPromptFragment(rs.getString("prompt_fragment"));
        vo.setGuardrails(runtimePromptText(rs.getString("guardrails_json")));
        vo.setNegativeRules(runtimePromptText(rs.getString("negative_rules_json")));
        vo.setOutputContract(runtimePromptText(rs.getString("output_contract_json")));
        vo.setInputSchema(parseJsonObject(readStringIfAvailable(rs, "input_schema_json", runtimeInputSchemaColumnAvailable)));
        vo.setOutputSchema(parseJsonObject(readStringIfAvailable(rs, "output_schema_json", runtimeOutputSchemaColumnAvailable)));
        vo.setRolloutPolicy(parseJsonObject(readStringIfAvailable(rs, "rollout_policy_json", runtimeRolloutPolicyColumnAvailable)));
        vo.setSource("backend");
        return vo;
    }

    private void auditSkill(SkillCandidateVO candidate, Long relatedCandidateId, String eventType, String previousStatus,
                            String newStatus, Map<String, Object> details) {
        if (!hasTable("ai_skill_lifecycle_audit") || candidate == null) {
            return;
        }
        jdbcTemplate.update("""
                insert into ai_skill_lifecycle_audit(
                    skill_id, candidate_id, related_candidate_id, event_type, previous_status, new_status,
                    version, content_hash, actor_user_id, source_trace_id, details_json
                ) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            candidate.getSkillId(), candidate.getId(), relatedCandidateId, eventType, previousStatus, newStatus,
            candidate.getVersion(), candidate.getContentHash(), currentAdminId(), candidate.getSourceTraceId(), writeJson(details));
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
        return raw == null ? Collections.emptyMap() : parseEvalResult(raw);
    }

    private Map<String, Object> parseJsonObject(String rawJson) {
        String raw = trimToNull(rawJson);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "runtime skill schema must be a JSON object");
        }
    }

    private List<String> readStringList(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            String text = item == null ? null : trimToNull(String.valueOf(item));
            if (text != null && !values.contains(text)) values.add(text);
        }
        return values;
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String text = trimToNull(value);
            if (text != null) normalized.add(text);
        }
        return List.copyOf(normalized);
    }

    private List<String> parseStringListStrict(String rawJson, String label) {
        String raw = trimToNull(rawJson);
        if (raw == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " must be a JSON array");
        }
        try {
            List<Object> values = objectMapper.readValue(raw, LIST_TYPE);
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof String text) || trimToNull(text) == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " must contain text values");
                }
                if (!result.contains(text.trim())) result.add(text.trim());
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill " + label + " must be a JSON array");
        }
    }

    private String runtimePromptText(String rawJson) {
        String raw = trimToNull(rawJson);
        if (raw == null) return null;
        try {
            Object value = objectMapper.readValue(raw, Object.class);
            if (value instanceof String text) return trimToNull(text);
            if (value instanceof Iterable<?> values) {
                List<String> items = new ArrayList<>();
                for (Object item : values) {
                    if (!(item instanceof String text) || trimToNull(text) == null) return null;
                    items.add(text.trim());
                }
                return items.isEmpty() ? null : String.join("\n", items);
            }
            if (value instanceof Map<?, ?>) return writeJson(value);
            return null;
        } catch (JsonProcessingException ex) {
            return raw;
        }
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "failed to write skill runtime json");
        }
    }

    private String jsonValue(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return trimToNull(text);
        return writeJson(value);
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) return map.get(key);
        }
        Object skill = map.get("skill");
        if (skill instanceof Map<?, ?> nested) {
            for (String key : keys) {
                if (nested.containsKey(key) && nested.get(key) != null) return nested.get(key);
            }
        }
        return null;
    }

    private String firstString(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private void assertMetricAtLeast(Map<?, ?> metrics, String metricName, double threshold) {
        assertMetricAtLeast(metricValue(metrics.get(metricName)), metricName, threshold);
    }

    private void assertMetricAtLeast(Double value, String metricName, double threshold) {
        if (value == null || !Double.isFinite(value) || value < 0.0d || value > 1.0d || value < threshold) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "skill eval metric " + metricName + " must be >= " + threshold);
        }
    }

    private Double metricValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String normalizeDecision(String value) {
        String decision = requireText(value, "decision is required").toUpperCase(Locale.ROOT);
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported decision");
        }
        return decision;
    }

    private String lifecycleFromLegacy(String value) {
        String status = trimToNull(value);
        if (status == null) return "DRAFT";
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "DRAFT", "PENDING" -> "DRAFT";
            case "APPROVED" -> "APPROVED";
            case "ACTIVE", "PUBLISHED" -> "ACTIVE";
            case "REVOKED", "DISABLED", "REJECTED", "ROLLED_BACK" -> "REVOKED";
            default -> "DRAFT";
        };
    }

    private void addCandidateColumn(List<String> columns, List<Object> values, String column, Object value, boolean available) {
        if (!available) return;
        columns.add(column);
        values.add(value);
    }

    private Long insertReturningId(String tableName, List<String> columns, List<Object> values, String errorMessage) {
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into " + tableName + "(" + String.join(", ", columns) + ") values(" + placeholders + ")",
                new String[]{"id"}
            );
            for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
            return statement;
        }, holder);
        for (Map<String, Object> keys : holder.getKeyList()) {
            for (Map.Entry<String, Object> entry : keys.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                    return number.longValue();
                }
            }
            if (keys.size() == 1) {
                Object value = keys.values().iterator().next();
                if (value instanceof Number number) return number.longValue();
            }
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, errorMessage);
    }

    private <T> T inTransaction(Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) throw new BusinessException(ResultCode.INTERNAL_ERROR, "skill lifecycle transaction returned no result");
        return result;
    }

    private Long currentAdminId() {
        AuthUser user = AuthUserHolder.get();
        return user == null ? null : user.getUserId();
    }

    private String sha256Hex(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : hash) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
                    if (rs.next()) return true;
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
                    if (rs.next()) return true;
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
        return available ? rs.getString(columnName) : null;
    }

    private Double readNullableDouble(ResultSet rs, String columnName, boolean available) throws SQLException {
        if (!available) return null;
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    private String buildWhere(String status, List<Object> args) {
        String normalized = trimToNull(status);
        if (normalized == null) return "";
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (lifecycleStatusColumnAvailable && SKILL_LIFECYCLE_STATUSES.contains(upper)) {
            args.add(upper);
            return " where lifecycle_status = ?";
        }
        args.add(upper);
        return " where status = ?";
    }

    private int normalizePage(Integer page) {
        if (page == null || page <= 0) return 1;
        return Math.min(page, 10000);
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) return 20;
        return Math.min(pageSize, 50);
    }

    private void requireGovernedCandidateSchema() {
        if (!hasTable("ai_skill_candidate") || !contentColumnAvailable || !sourceTraceIdColumnAvailable
            || !lifecycleStatusColumnAvailable || !versionColumnAvailable || !contentHashColumnAvailable
            || !inputSchemaColumnAvailable || !outputSchemaColumnAvailable || !descriptionColumnAvailable
            || !requestedCapabilitiesColumnAvailable || !skillMetadataColumnAvailable
            || !rolloutPolicyColumnAvailable) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "phase27 governed skill candidate schema is required");
        }
        requireSkillAuditTable();
    }

    private void requireGovernedRuntimeSchema() {
        if (!hasTable("ai_runtime_skill") || !runtimeContentHashColumnAvailable || !runtimeInputSchemaColumnAvailable
            || !runtimeOutputSchemaColumnAvailable || !runtimeRolloutPolicyColumnAvailable
            || !runtimeActivatedByColumnAvailable || !runtimeActivatedAtColumnAvailable
            || !runtimeRollbackVersionColumnAvailable || !runtimeDescriptionColumnAvailable
            || !runtimeRequestedCapabilitiesColumnAvailable || !runtimeSkillMetadataColumnAvailable) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "phase27 governed runtime skill schema is required");
        }
    }

    private void requireSkillAuditTable() {
        if (!hasTable("ai_skill_lifecycle_audit")) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "phase23 skill lifecycle audit table is required");
        }
    }

    private void requireAdmin() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || !user.getRoles().contains("ADMIN")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin role required");
        }
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) throw new BusinessException(ResultCode.BAD_REQUEST, message);
        return normalized;
    }

    private String rawContent(String value) {
        if (value == null || value.isBlank()) return null;
        return value;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record SkillContract(
                                 String version,
                                 String contentHash,
                                 List<String> intents,
                                 List<String> requestedCapabilities,
                                 String inputSchemaJson, String outputSchemaJson, String rolloutPolicyJson) {
        private SkillContract {
            intents = intents == null ? List.of() : List.copyOf(intents);
            requestedCapabilities = requestedCapabilities == null
                ? List.of()
                : List.copyOf(requestedCapabilities);
        }
    }

    private record SkillImport(
        String skillId,
        String title,
        String description,
        String instructions,
        List<String> requestedCapabilities,
        String skillMetadataJson,
        boolean standardFormat
    ) {
        private SkillImport {
            requestedCapabilities = requestedCapabilities == null
                ? List.of()
                : List.copyOf(requestedCapabilities);
        }
    }
}

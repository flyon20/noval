package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.dto.AiMemoryCandidateRequest;
import com.novelanalyzer.modules.knowledge.vo.AiMemoryVO;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class KnowledgeMemoryService {

    private static final String STATUS_CANDIDATE = "CANDIDATE";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String STATUS_STALE = "STALE";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_SCOPES = Set.of("project", "user", "thread");
    private static final Set<String> ALLOWED_MEMORY_TYPES = Set.of(
        "fact", "preference", "constraint", "risk", "decision", "revision"
    );
    private static final Pattern FACT_KEY_PATTERN = Pattern.compile("^[\\p{L}\\p{N}._:-]{1,160}$");
    private static final Pattern CANDIDATE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,200}$");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate committedTransactionTemplate;
    private final boolean candidateConversationIdColumnAvailable;
    private final boolean candidateScopeColumnAvailable;
    private final boolean candidateMemoryTypeColumnAvailable;
    private final boolean candidateSummaryColumnAvailable;
    private final boolean candidateConfidenceColumnAvailable;
    private final boolean candidateExpiresAtColumnAvailable;
    private final boolean candidateFactKeyColumnAvailable;
    private final boolean candidateCandidateKeyColumnAvailable;
    private final boolean candidateProvenanceColumnAvailable;
    private final boolean candidateEvidenceColumnAvailable;
    private final boolean candidateEvidenceIdsColumnAvailable;
    private final boolean candidateChapterVersionsColumnAvailable;
    private final boolean candidateIndexGenerationColumnAvailable;
    private final boolean candidateExtractorVersionColumnAvailable;
    private final boolean candidateSupersedesColumnAvailable;
    private final boolean candidateConflictsWithColumnAvailable;
    private final boolean candidateLifecycleStatusColumnAvailable;
    private final boolean memoryFactKeyColumnAvailable;
    private final boolean memoryProvenanceColumnAvailable;
    private final boolean memoryEvidenceColumnAvailable;
    private final boolean memoryEvidenceIdsColumnAvailable;
    private final boolean memoryChapterVersionsColumnAvailable;
    private final boolean memoryIndexGenerationColumnAvailable;
    private final boolean memoryExtractorVersionColumnAvailable;
    private final boolean memorySupersedesColumnAvailable;
    private final boolean memoryConfirmedByColumnAvailable;
    private final boolean memoryConfirmedAtColumnAvailable;
    private final boolean memoryStaleAtColumnAvailable;
    private final boolean memoryLifecycleStatusColumnAvailable;

    public KnowledgeMemoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            Objects.requireNonNull(jdbcTemplate.getDataSource(), "dataSource is required")
        );
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.committedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.committedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.candidateConversationIdColumnAvailable = hasColumn("ai_memory_candidate", "conversation_id");
        this.candidateScopeColumnAvailable = hasColumn("ai_memory_candidate", "scope");
        this.candidateMemoryTypeColumnAvailable = hasColumn("ai_memory_candidate", "memory_type");
        this.candidateSummaryColumnAvailable = hasColumn("ai_memory_candidate", "summary");
        this.candidateConfidenceColumnAvailable = hasColumn("ai_memory_candidate", "confidence");
        this.candidateExpiresAtColumnAvailable = hasColumn("ai_memory_candidate", "expires_at");
        this.candidateFactKeyColumnAvailable = hasColumn("ai_memory_candidate", "fact_key");
        this.candidateCandidateKeyColumnAvailable = hasColumn("ai_memory_candidate", "candidate_key");
        this.candidateProvenanceColumnAvailable = hasColumn("ai_memory_candidate", "provenance_json");
        this.candidateEvidenceColumnAvailable = hasColumn("ai_memory_candidate", "evidence_json");
        this.candidateEvidenceIdsColumnAvailable = hasColumn("ai_memory_candidate", "source_evidence_ids_json");
        this.candidateChapterVersionsColumnAvailable = hasColumn("ai_memory_candidate", "source_chapter_versions_json");
        this.candidateIndexGenerationColumnAvailable = hasColumn("ai_memory_candidate", "index_generation");
        this.candidateExtractorVersionColumnAvailable = hasColumn("ai_memory_candidate", "extractor_version");
        this.candidateSupersedesColumnAvailable = hasColumn("ai_memory_candidate", "supersedes_id");
        this.candidateConflictsWithColumnAvailable = hasColumn("ai_memory_candidate", "conflicts_with_id");
        this.candidateLifecycleStatusColumnAvailable = hasColumn("ai_memory_candidate", "lifecycle_status");
        this.memoryFactKeyColumnAvailable = hasColumn("ai_memory_item", "fact_key");
        this.memoryProvenanceColumnAvailable = hasColumn("ai_memory_item", "provenance_json");
        this.memoryEvidenceColumnAvailable = hasColumn("ai_memory_item", "evidence_json");
        this.memoryEvidenceIdsColumnAvailable = hasColumn("ai_memory_item", "source_evidence_ids_json");
        this.memoryChapterVersionsColumnAvailable = hasColumn("ai_memory_item", "source_chapter_versions_json");
        this.memoryIndexGenerationColumnAvailable = hasColumn("ai_memory_item", "index_generation");
        this.memoryExtractorVersionColumnAvailable = hasColumn("ai_memory_item", "extractor_version");
        this.memorySupersedesColumnAvailable = hasColumn("ai_memory_item", "supersedes_id");
        this.memoryConfirmedByColumnAvailable = hasColumn("ai_memory_item", "confirmed_by");
        this.memoryConfirmedAtColumnAvailable = hasColumn("ai_memory_item", "confirmed_at");
        this.memoryStaleAtColumnAvailable = hasColumn("ai_memory_item", "stale_at");
        this.memoryLifecycleStatusColumnAvailable = hasColumn("ai_memory_item", "lifecycle_status");
        backfillLegacyFactKeys();
    }

    public Long createCandidate(Long userId,
                                Long projectId,
                                String conversationId,
                                String scope,
                                String memoryType,
                                String content,
                                String summary,
                                double confidence,
                                String sourceTraceId,
                                int ttlDays) {
        AiMemoryCandidateRequest request = new AiMemoryCandidateRequest();
        request.setUserId(userId);
        request.setProjectId(projectId);
        request.setConversationId(conversationId);
        request.setScope(scope);
        request.setMemoryType(memoryType);
        request.setContent(content);
        request.setSummary(summary);
        request.setConfidence(confidence);
        request.setSourceTraceId(sourceTraceId);
        request.setTtlDays(ttlDays);
        return createCandidate(request);
    }

    public Long createCandidate(AiMemoryCandidateRequest request) {
        Long userId = request == null ? null : request.getUserId();
        requireUser(userId);
        String scope = normalizeEnum(request.getScope(), ALLOWED_SCOPES, "memory scope is invalid");
        String memoryType = normalizeEnum(request.getMemoryType(), ALLOWED_MEMORY_TYPES, "memory type is invalid");
        String content = requireText(request.getContent(), "memory content is required");
        double confidence = clampConfidence(request.getConfidence());
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(request.getTtlDays() == null ? 30 : request.getTtlDays());
        String factKey = normalizeFactKey(request.getFactKey(), scope, memoryType, content);
        String candidateKey = normalizeCandidateKey(request.getCandidateKey());

        Long effectiveProjectId = "user".equals(scope) && candidateScopeColumnAvailable
            ? null
            : request.getProjectId();
        Long existingCandidateId = findCandidateIdByCandidateKey(userId, candidateKey);
        if (existingCandidateId != null) return existingCandidateId;
        List<String> columns = new ArrayList<>(List.of(
            "project_id", "user_id", "candidate_type", "content", "status", "source_trace_id"
        ));
        List<Object> values = new ArrayList<>();
        Collections.addAll(values,
            effectiveProjectId, userId, scope + "." + memoryType, content, "candidate",
            trimToNull(request.getSourceTraceId())
        );
        addColumn(columns, values, "conversation_id", trimToNull(request.getConversationId()), candidateConversationIdColumnAvailable);
        addColumn(columns, values, "scope", scope, candidateScopeColumnAvailable);
        addColumn(columns, values, "memory_type", memoryType, candidateMemoryTypeColumnAvailable);
        addColumn(columns, values, "summary", trimToNull(request.getSummary()), candidateSummaryColumnAvailable);
        addColumn(columns, values, "confidence", confidence, candidateConfidenceColumnAvailable);
        addColumn(columns, values, "expires_at", expiresAt, candidateExpiresAtColumnAvailable);
        addColumn(columns, values, "lifecycle_status", STATUS_CANDIDATE, candidateLifecycleStatusColumnAvailable);
        addColumn(columns, values, "fact_key", factKey, candidateFactKeyColumnAvailable);
        addColumn(columns, values, "candidate_key", candidateKey, candidateCandidateKeyColumnAvailable);
        addColumn(columns, values, "provenance_json", validateCompactJson(request.getProvenanceJson(), "provenanceJson"), candidateProvenanceColumnAvailable);
        addColumn(columns, values, "evidence_json", validateCompactJson(request.getEvidenceJson(), "evidenceJson"), candidateEvidenceColumnAvailable);
        addColumn(columns, values, "source_evidence_ids_json", validateCompactJson(request.getSourceEvidenceIdsJson(), "sourceEvidenceIdsJson"), candidateEvidenceIdsColumnAvailable);
        addColumn(columns, values, "source_chapter_versions_json", validateCompactJson(request.getSourceChapterVersionsJson(), "sourceChapterVersionsJson"), candidateChapterVersionsColumnAvailable);
        addColumn(columns, values, "index_generation", trimToNull(request.getIndexGeneration()), candidateIndexGenerationColumnAvailable);
        addColumn(columns, values, "extractor_version", trimToNull(request.getExtractorVersion()), candidateExtractorVersionColumnAvailable);
        addColumn(columns, values, "supersedes_id", request.getSupersedesId(), candidateSupersedesColumnAvailable);
        try {
            return insertReturningId("ai_memory_candidate", columns, values, "memory candidate id missing");
        } catch (DuplicateKeyException ex) {
            Long racedCandidateId = findCandidateIdByCandidateKey(userId, candidateKey);
            if (racedCandidateId != null) return racedCandidateId;
            throw ex;
        }
    }

    public AiMemoryVO promoteCandidate(Long candidateId, Long userId) {
        requireUser(userId);
        return promoteCandidateCommitted(candidateId, userId, userId);
    }

    public void rejectCandidate(Long candidateId, Long userId) {
        requireUser(userId);
        inTransaction(() -> {
            AiMemoryVO candidate = findCandidate(candidateId, userId, true);
            updateCandidateStatus(candidate.getId(), "rejected", STATUS_REJECTED, null);
            auditMemory(null, candidate.getId(), "REJECTED", candidate.getLifecycleStatus(), STATUS_REJECTED, userId,
                candidate.getSourceTraceId(), Map.of());
            return 0;
        });
    }

    public int expireCandidates() {
        if (!candidateExpiresAtColumnAvailable) return 0;
        String lifecycle = candidateLifecycleStatusColumnAvailable ? " and lifecycle_status = 'CANDIDATE'" : "";
        int expired = jdbcTemplate.update("update ai_memory_candidate set status = 'expired'"
            + (candidateLifecycleStatusColumnAvailable ? ", lifecycle_status = 'STALE'" : "")
            + ", updated_at = current_timestamp where lower(status) in ('candidate', 'pending')" + lifecycle
            + " and expires_at is not null and expires_at < current_timestamp");
        return expired;
    }

    public List<AiMemoryVO> searchConfirmedMemory(Long userId, Long projectId, String scope, int limit) {
        requireUser(userId);
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        String confirmedPredicate = memoryLifecycleStatusColumnAvailable ? "lifecycle_status = 'CONFIRMED'" : "lower(status) = 'confirmed'";
        return jdbcTemplate.query(memorySelectColumns() + " from ai_memory_item where user_id = ?"
                + " and (? is null or project_id = ?) and (? is null or scope = ?) and " + confirmedPredicate
                + " and deleted_at is null order by updated_at desc, id desc limit ?",
            (rs, rowNum) -> mapMemory(rs), userId, projectId, projectId, trimToNull(scope), trimToNull(scope), effectiveLimit);
    }

    public List<AiMemoryVO> listMemoriesForAdmin(Long userId, Long projectId, String status, String scope, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        StatusFilter filter = memoryStatusFilter(status, false);
        List<Object> args = new ArrayList<>(List.of(userId, userId, projectId, projectId, trimToNull(scope), trimToNull(scope)));
        String sql = memorySelectColumns() + " from ai_memory_item where (? is null or user_id = ?) and (? is null or project_id = ?)"
            + " and (? is null or scope = ?) and deleted_at is null";
        if (filter.sql() != null) {
            sql += " and " + filter.sql();
            args.add(filter.value());
        }
        sql += " order by updated_at desc, id desc limit ?";
        args.add(effectiveLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapMemory(rs), args.toArray());
    }

    public List<AiMemoryVO> listCandidateMemoriesForAdmin(Long userId, Long projectId, String status, String scope, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        StatusFilter filter = memoryStatusFilter(status, true);
        List<Object> args = new ArrayList<>(List.of(userId, userId, projectId, projectId, trimToNull(scope), trimToNull(scope)));
        String sql = candidateSelectColumns() + " from ai_memory_candidate where (? is null or user_id = ?) and (? is null or project_id = ?)"
            + " and (? is null or scope = ?)";
        if (filter.sql() != null) {
            sql += " and " + filter.sql();
            args.add(filter.value());
        }
        sql += " order by updated_at desc, id desc limit ?";
        args.add(effectiveLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapCandidate(rs), args.toArray());
    }

    public AiMemoryVO reviewCandidateForAdmin(Long candidateId, String decision) {
        String normalizedDecision = requireText(decision, "decision is required").toUpperCase(Locale.ROOT);
        if ("APPROVED".equals(normalizedDecision)) {
            return promoteCandidateCommitted(candidateId, null, null);
        }
        if ("REJECTED".equals(normalizedDecision)) {
            return inTransaction(() -> {
                AiMemoryVO candidate = findCandidate(candidateId, null, true);
                String previousStatus = candidate.getLifecycleStatus();
                updateCandidateStatus(candidate.getId(), "rejected", STATUS_REJECTED, null);
                candidate.setStatus("REJECTED");
                candidate.setLifecycleStatus(STATUS_REJECTED);
                auditMemory(null, candidate.getId(), "REJECTED", previousStatus, STATUS_REJECTED, null,
                    candidate.getSourceTraceId(), Map.of());
                return candidate;
            });
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "decision must be APPROVED or REJECTED");
    }

    public void deleteMemoryForAdmin(Long memoryId) {
        if (memoryId == null) throw new BusinessException(ResultCode.BAD_REQUEST, "memoryId is required");
        inTransaction(() -> {
            AiMemoryVO existing = findMemory(memoryId);
            String sql = "update ai_memory_item set status = 'deleted', deleted_at = current_timestamp, updated_at = current_timestamp";
            if (memoryLifecycleStatusColumnAvailable) sql += ", lifecycle_status = 'STALE'";
            if (memoryStaleAtColumnAvailable) sql += ", stale_at = current_timestamp";
            sql += " where id = ?";
            jdbcTemplate.update(sql, memoryId);
            auditMemory(memoryId, null, "STALED", existing.getLifecycleStatus(), STATUS_STALE, null, existing.getSourceTraceId(), Map.of("reason", "deleted"));
            return 0;
        });
    }

    private AiMemoryVO promoteCandidateInternal(Long candidateId, Long requiredUserId, Long confirmedBy) {
        AiMemoryVO candidate = findCandidate(candidateId, requiredUserId, true);
        AiMemoryVO existing = findConfirmedByFactKey(candidate);
        if (existing != null && !Objects.equals(candidate.getSupersedesId(), existing.getId())) {
            if (Objects.equals(normalizeComparableContent(existing.getContent()), normalizeComparableContent(candidate.getContent()))) {
                updateCandidateStatus(candidate.getId(), "confirmed", STATUS_CONFIRMED, null);
                auditMemory(existing.getId(), candidate.getId(), "CONFIRMED_REUSED", STATUS_CONFIRMED, STATUS_CONFIRMED,
                    confirmedBy, candidate.getSourceTraceId(), Map.of("factKey", candidate.getFactKey()));
                return existing;
            }
            setCandidateConflict(candidate.getId(), existing.getId());
            auditMemory(existing.getId(), candidate.getId(), "CONFLICT_DETECTED", STATUS_CANDIDATE, STATUS_CANDIDATE,
                confirmedBy, candidate.getSourceTraceId(), Map.of("factKey", candidate.getFactKey()));
            throw new MemoryConflictDetected("memory candidate conflicts with confirmed memory");
        }
        if (candidate.getSupersedesId() != null) {
            supersedeMemory(candidate.getSupersedesId(), candidate, confirmedBy);
        }
        Long memoryId = insertConfirmedMemory(candidate, confirmedBy);
        updateCandidateStatus(candidate.getId(), "confirmed", STATUS_CONFIRMED, null);
        candidate.setId(memoryId);
        candidate.setStatus("confirmed");
        candidate.setLifecycleStatus(STATUS_CONFIRMED);
        candidate.setConfirmedBy(confirmedBy);
        auditMemory(memoryId, candidateId, "CONFIRMED", STATUS_CANDIDATE, STATUS_CONFIRMED, confirmedBy,
            candidate.getSourceTraceId(), Map.of("factKey", String.valueOf(candidate.getFactKey())));
        return candidate;
    }

    private AiMemoryVO promoteCandidateCommitted(Long candidateId, Long requiredUserId, Long confirmedBy) {
        PromotionOutcome outcome = inCommittedTransaction(() -> {
            try {
                return new PromotionOutcome(promoteCandidateInternal(candidateId, requiredUserId, confirmedBy), null);
            } catch (MemoryConflictDetected conflict) {
                return new PromotionOutcome(null, conflict.getMessage());
            }
        });
        if (outcome.conflictMessage() != null) {
            throw new BusinessException(ResultCode.CONFLICT, outcome.conflictMessage());
        }
        return outcome.memory();
    }

    private AiMemoryVO findConfirmedByFactKey(AiMemoryVO candidate) {
        if (!memoryFactKeyColumnAvailable || trimToNull(candidate.getFactKey()) == null) return null;
        String confirmedPredicate = memoryLifecycleStatusColumnAvailable ? "lifecycle_status = 'CONFIRMED'" : "lower(status) = 'confirmed'";
        List<AiMemoryVO> memories = jdbcTemplate.query(memorySelectColumns() + " from ai_memory_item where user_id = ?"
                + " and ((project_id = ?) or (project_id is null and ? is null)) and scope = ? and memory_type = ?"
                + " and fact_key = ? and " + confirmedPredicate + " and deleted_at is null order by id desc limit 1 for update",
            (rs, rowNum) -> mapMemory(rs), candidate.getUserId(), candidate.getProjectId(), candidate.getProjectId(),
            candidate.getScope(), candidate.getMemoryType(), candidate.getFactKey());
        if (memories.isEmpty()) return null;
        return memories.get(0);
    }

    private Long insertConfirmedMemory(AiMemoryVO candidate, Long confirmedBy) {
        List<String> columns = new ArrayList<>(List.of(
            "user_id", "project_id", "conversation_id", "scope", "memory_type", "content", "summary",
            "confidence", "status", "source_trace_id"
        ));
        List<Object> values = new ArrayList<>();
        Collections.addAll(values,
            candidate.getUserId(), candidate.getProjectId(), candidate.getConversationId(), candidate.getScope(),
            candidate.getMemoryType(), candidate.getContent(), candidate.getSummary(), candidate.getConfidence(),
            "confirmed", candidate.getSourceTraceId()
        );
        addColumn(columns, values, "lifecycle_status", STATUS_CONFIRMED, memoryLifecycleStatusColumnAvailable);
        addColumn(columns, values, "fact_key", candidate.getFactKey(), memoryFactKeyColumnAvailable);
        addColumn(columns, values, "provenance_json", candidate.getProvenanceJson(), memoryProvenanceColumnAvailable);
        addColumn(columns, values, "evidence_json", candidate.getEvidenceJson(), memoryEvidenceColumnAvailable);
        addColumn(columns, values, "source_evidence_ids_json", candidate.getSourceEvidenceIdsJson(), memoryEvidenceIdsColumnAvailable);
        addColumn(columns, values, "source_chapter_versions_json", candidate.getSourceChapterVersionsJson(), memoryChapterVersionsColumnAvailable);
        addColumn(columns, values, "index_generation", candidate.getIndexGeneration(), memoryIndexGenerationColumnAvailable);
        addColumn(columns, values, "extractor_version", candidate.getExtractorVersion(), memoryExtractorVersionColumnAvailable);
        addColumn(columns, values, "supersedes_id", candidate.getSupersedesId(), memorySupersedesColumnAvailable);
        addColumn(columns, values, "confirmed_by", confirmedBy, memoryConfirmedByColumnAvailable);
        if (memoryConfirmedAtColumnAvailable) {
            columns.add("confirmed_at");
            values.add(LocalDateTime.now());
        }
        return insertReturningId("ai_memory_item", columns, values, "memory id missing");
    }

    private void updateCandidateStatus(Long candidateId, String legacyStatus, String lifecycleStatus, Long conflictsWithId) {
        String sql = "update ai_memory_candidate set status = ?, updated_at = current_timestamp";
        List<Object> args = new ArrayList<>(List.of(legacyStatus));
        if (candidateLifecycleStatusColumnAvailable) {
            sql += ", lifecycle_status = ?";
            args.add(lifecycleStatus);
        }
        if (candidateConflictsWithColumnAvailable && conflictsWithId != null) {
            sql += ", conflicts_with_id = ?";
            args.add(conflictsWithId);
        }
        sql += " where id = ?";
        args.add(candidateId);
        jdbcTemplate.update(sql, args.toArray());
    }

    private void supersedeMemory(Long memoryId, AiMemoryVO replacement, Long actorUserId) {
        AiMemoryVO existing = findMemoryForUpdate(memoryId);
        if (!STATUS_CONFIRMED.equals(existing.getLifecycleStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "superseded memory must be confirmed");
        }
        if (!sameMemoryIdentity(existing, replacement)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "superseded memory must match the candidate owner and fact identity");
        }
        String sql = "update ai_memory_item set status = 'superseded', updated_at = current_timestamp";
        if (memoryLifecycleStatusColumnAvailable) sql += ", lifecycle_status = 'SUPERSEDED'";
        sql += " where id = ?";
        List<Object> args = new ArrayList<>(List.of(memoryId));
        if (memoryLifecycleStatusColumnAvailable) {
            sql += " and lifecycle_status = 'CONFIRMED'";
        } else {
            sql += " and lower(status) = 'confirmed'";
        }
        if (jdbcTemplate.update(sql, args.toArray()) != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "superseded memory changed concurrently");
        }
        auditMemory(memoryId, null, "SUPERSEDED", STATUS_CONFIRMED, STATUS_SUPERSEDED, actorUserId,
            replacement.getSourceTraceId(), Map.of("replacementFactKey", String.valueOf(replacement.getFactKey())));
    }

    private boolean sameMemoryIdentity(AiMemoryVO existing, AiMemoryVO replacement) {
        return Objects.equals(existing.getUserId(), replacement.getUserId())
            && Objects.equals(existing.getProjectId(), replacement.getProjectId())
            && Objects.equals(existing.getScope(), replacement.getScope())
            && Objects.equals(existing.getMemoryType(), replacement.getMemoryType())
            && existing.getFactKey() != null
            && Objects.equals(existing.getFactKey(), replacement.getFactKey());
    }

    private void setCandidateConflict(Long candidateId, Long conflictingMemoryId) {
        if (candidateConflictsWithColumnAvailable) {
            jdbcTemplate.update("update ai_memory_candidate set conflicts_with_id = ?, updated_at = current_timestamp where id = ?",
                conflictingMemoryId, candidateId);
        }
    }

    private AiMemoryVO findCandidate(Long candidateId, Long userId, boolean onlyCandidate) {
        String candidatePredicate = candidateLifecycleStatusColumnAvailable ? "lifecycle_status = 'CANDIDATE'" : "lower(status) in ('candidate', 'pending')";
        String sql = candidateSelectColumns() + " from ai_memory_candidate where id = ? and (? is null or user_id = ?)";
        if (onlyCandidate) sql += " and " + candidatePredicate + " for update";
        List<AiMemoryVO> candidates = jdbcTemplate.query(sql, (rs, rowNum) -> mapCandidate(rs), candidateId, userId, userId);
        if (candidates.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND, "memory candidate not found");
        return candidates.get(0);
    }

    private AiMemoryVO findMemory(Long memoryId) {
        List<AiMemoryVO> memories = jdbcTemplate.query(memorySelectColumns() + " from ai_memory_item where id = ?",
            (rs, rowNum) -> mapMemory(rs), memoryId);
        if (memories.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND, "memory not found");
        return memories.get(0);
    }

    private AiMemoryVO findMemoryForUpdate(Long memoryId) {
        List<AiMemoryVO> memories = jdbcTemplate.query(
            memorySelectColumns() + " from ai_memory_item where id = ? for update",
            (rs, rowNum) -> mapMemory(rs),
            memoryId
        );
        if (memories.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND, "memory not found");
        return memories.get(0);
    }

    private AiMemoryVO mapCandidate(ResultSet rs) throws SQLException {
        AiMemoryVO memory = mapMemoryBase(rs);
        memory.setFactKey(readString(rs, "fact_key", candidateFactKeyColumnAvailable));
        memory.setCandidateKey(readString(rs, "candidate_key", candidateCandidateKeyColumnAvailable));
        memory.setProvenanceJson(readString(rs, "provenance_json", candidateProvenanceColumnAvailable));
        memory.setEvidenceJson(readString(rs, "evidence_json", candidateEvidenceColumnAvailable));
        memory.setSourceEvidenceIdsJson(readString(rs, "source_evidence_ids_json", candidateEvidenceIdsColumnAvailable));
        memory.setSourceChapterVersionsJson(readString(rs, "source_chapter_versions_json", candidateChapterVersionsColumnAvailable));
        memory.setIndexGeneration(readString(rs, "index_generation", candidateIndexGenerationColumnAvailable));
        memory.setExtractorVersion(readString(rs, "extractor_version", candidateExtractorVersionColumnAvailable));
        memory.setSupersedesId(readLong(rs, "supersedes_id", candidateSupersedesColumnAvailable));
        memory.setConflictsWithId(readLong(rs, "conflicts_with_id", candidateConflictsWithColumnAvailable));
        return memory;
    }

    private AiMemoryVO mapMemory(ResultSet rs) throws SQLException {
        AiMemoryVO memory = mapMemoryBase(rs);
        memory.setFactKey(readString(rs, "fact_key", memoryFactKeyColumnAvailable));
        memory.setProvenanceJson(readString(rs, "provenance_json", memoryProvenanceColumnAvailable));
        memory.setEvidenceJson(readString(rs, "evidence_json", memoryEvidenceColumnAvailable));
        memory.setSourceEvidenceIdsJson(readString(rs, "source_evidence_ids_json", memoryEvidenceIdsColumnAvailable));
        memory.setSourceChapterVersionsJson(readString(rs, "source_chapter_versions_json", memoryChapterVersionsColumnAvailable));
        memory.setIndexGeneration(readString(rs, "index_generation", memoryIndexGenerationColumnAvailable));
        memory.setExtractorVersion(readString(rs, "extractor_version", memoryExtractorVersionColumnAvailable));
        memory.setSupersedesId(readLong(rs, "supersedes_id", memorySupersedesColumnAvailable));
        memory.setConfirmedBy(readLong(rs, "confirmed_by", memoryConfirmedByColumnAvailable));
        return memory;
    }

    private AiMemoryVO mapMemoryBase(ResultSet rs) throws SQLException {
        AiMemoryVO vo = new AiMemoryVO();
        vo.setId(rs.getLong("id"));
        vo.setUserId(rs.getLong("user_id"));
        long projectId = rs.getLong("project_id");
        vo.setProjectId(rs.wasNull() ? null : projectId);
        vo.setConversationId(rs.getString("conversation_id"));
        vo.setScope(rs.getString("scope"));
        vo.setMemoryType(rs.getString("memory_type"));
        vo.setContent(rs.getString("content"));
        vo.setSummary(rs.getString("summary"));
        double confidence = rs.getDouble("confidence");
        vo.setConfidence(rs.wasNull() ? null : confidence);
        String legacyStatus = rs.getString("status");
        vo.setLegacyStatus(legacyStatus);
        vo.setStatus(legacyStatus);
        boolean lifecycleAvailable = hasCandidateColumnsInResult(rs) ? candidateLifecycleStatusColumnAvailable : memoryLifecycleStatusColumnAvailable;
        if (lifecycleAvailable) {
            String lifecycle = rs.getString("lifecycle_status");
            vo.setLifecycleStatus(lifecycle == null ? lifecycleStatus(legacyStatus) : lifecycle.toUpperCase(Locale.ROOT));
        } else {
            vo.setLifecycleStatus(lifecycleStatus(legacyStatus));
        }
        vo.setSourceTraceId(rs.getString("source_trace_id"));
        return vo;
    }

    private boolean hasCandidateColumnsInResult(ResultSet rs) throws SQLException {
        try {
            rs.findColumn("conflicts_with_id");
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    private String candidateSelectColumns() {
        String columns = "select id, user_id, project_id"
            + optionalSelectColumn("conversation_id", candidateConversationIdColumnAvailable)
            + optionalSelectColumn("scope", candidateScopeColumnAvailable)
            + optionalSelectColumn("memory_type", candidateMemoryTypeColumnAvailable)
            + ", content"
            + optionalSelectColumn("summary", candidateSummaryColumnAvailable)
            + optionalSelectColumn("confidence", candidateConfidenceColumnAvailable)
            + ", status, source_trace_id";
        if (candidateLifecycleStatusColumnAvailable) columns += ", lifecycle_status";
        if (candidateFactKeyColumnAvailable) columns += ", fact_key";
        if (candidateCandidateKeyColumnAvailable) columns += ", candidate_key";
        if (candidateProvenanceColumnAvailable) columns += ", provenance_json";
        if (candidateEvidenceColumnAvailable) columns += ", evidence_json";
        if (candidateEvidenceIdsColumnAvailable) columns += ", source_evidence_ids_json";
        if (candidateChapterVersionsColumnAvailable) columns += ", source_chapter_versions_json";
        if (candidateIndexGenerationColumnAvailable) columns += ", index_generation";
        if (candidateExtractorVersionColumnAvailable) columns += ", extractor_version";
        if (candidateSupersedesColumnAvailable) columns += ", supersedes_id";
        if (candidateConflictsWithColumnAvailable) columns += ", conflicts_with_id";
        return columns;
    }

    private String memorySelectColumns() {
        String columns = "select id, user_id, project_id, conversation_id, scope, memory_type, content, summary, confidence, status, source_trace_id";
        if (memoryLifecycleStatusColumnAvailable) columns += ", lifecycle_status";
        if (memoryFactKeyColumnAvailable) columns += ", fact_key";
        if (memoryProvenanceColumnAvailable) columns += ", provenance_json";
        if (memoryEvidenceColumnAvailable) columns += ", evidence_json";
        if (memoryEvidenceIdsColumnAvailable) columns += ", source_evidence_ids_json";
        if (memoryChapterVersionsColumnAvailable) columns += ", source_chapter_versions_json";
        if (memoryIndexGenerationColumnAvailable) columns += ", index_generation";
        if (memoryExtractorVersionColumnAvailable) columns += ", extractor_version";
        if (memorySupersedesColumnAvailable) columns += ", supersedes_id";
        if (memoryConfirmedByColumnAvailable) columns += ", confirmed_by";
        return columns;
    }

    private void auditMemory(Long memoryId, Long candidateId, String eventType, String previousStatus, String newStatus,
                             Long actorUserId, String sourceTraceId, Map<String, Object> details) {
        if (!hasTable("ai_memory_lifecycle_audit")) return;
        jdbcTemplate.update("""
                insert into ai_memory_lifecycle_audit(
                    memory_id, candidate_id, event_type, previous_status, new_status, actor_user_id, source_trace_id, details_json
                ) values(?, ?, ?, ?, ?, ?, ?, ?)
                """, memoryId, candidateId, eventType, previousStatus, newStatus, actorUserId, sourceTraceId,
            details == null || details.isEmpty() ? null : toJson(details));
    }

    private StatusFilter memoryStatusFilter(String status, boolean candidate) {
        String normalized = trimToNull(status);
        if (normalized == null) return new StatusFilter(null, null);
        String upper = lifecycleStatus(normalized);
        boolean lifecycleColumn = candidate ? candidateLifecycleStatusColumnAvailable : memoryLifecycleStatusColumnAvailable;
        if (lifecycleColumn) return new StatusFilter("lifecycle_status = ?", upper);
        return new StatusFilter("lower(status) = ?", legacyStatusFor(upper).toLowerCase(Locale.ROOT));
    }

    private String lifecycleStatus(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return STATUS_CANDIDATE;
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "CANDIDATE", "PENDING" -> STATUS_CANDIDATE;
            case "CONFIRMED", "APPROVED" -> STATUS_CONFIRMED;
            case "REJECTED" -> STATUS_REJECTED;
            case "SUPERSEDED" -> STATUS_SUPERSEDED;
            case "STALE", "EXPIRED", "DELETED" -> STATUS_STALE;
            default -> normalized.toUpperCase(Locale.ROOT);
        };
    }

    private String legacyStatusFor(String lifecycleStatus) {
        return switch (lifecycleStatus) {
            case STATUS_CANDIDATE -> "candidate";
            case STATUS_CONFIRMED -> "confirmed";
            case STATUS_REJECTED -> "rejected";
            case STATUS_SUPERSEDED -> "superseded";
            case STATUS_STALE -> "stale";
            default -> lifecycleStatus.toLowerCase(Locale.ROOT);
        };
    }

    private String normalizeFactKey(String requested, String scope, String memoryType, String content) {
        String normalized = trimToNull(requested);
        if (normalized == null) return deriveFactKey(scope, memoryType, content);
        normalized = normalized.replaceAll("\\s+", "");
        if (!FACT_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "memory factKey is invalid");
        }
        return normalized;
    }

    private String normalizeCandidateKey(String requested) {
        String normalized = trimToNull(requested);
        if (normalized == null) return null;
        if (!CANDIDATE_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "memory candidateKey is invalid");
        }
        return normalized;
    }

    private String deriveFactKey(String scope, String memoryType, String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        normalized = normalized.replaceAll(
            "\\b(?:not|no|never|cannot|can't|cant|can|isn't|isnt|doesn't|doesnt|without)\\b",
            " "
        );
        for (String marker : List.of(
            "\u4e0d\u80fd", "\u4e0d\u4f1a", "\u4e0d\u518d", "\u4e0d",
            "\u6ca1\u6709", "\u65e0", "\u53ef\u4ee5", "\u80fd\u591f"
        )) {
            normalized = normalized.replace(marker, "");
        }
        normalized = normalized.trim().replaceAll("\\s+", " ");
        return scope + "." + memoryType + "." + sha256Hex(normalized.isEmpty() ? content : normalized).substring(0, 24);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private Long findCandidateIdByCandidateKey(Long userId, String candidateKey) {
        if (!candidateCandidateKeyColumnAvailable || candidateKey == null) return null;
        List<Long> ids = jdbcTemplate.query(
            "select id from ai_memory_candidate where user_id = ? and candidate_key = ? order by id desc limit 1",
            (rs, rowNum) -> rs.getLong(1),
            userId,
            candidateKey
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void backfillLegacyFactKeys() {
        if (!memoryFactKeyColumnAvailable || !hasTable("ai_memory_item")) return;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, scope, memory_type, content from ai_memory_item where fact_key is null"
                    + " and scope is not null and memory_type is not null and content is not null limit 500"
            );
            if (rows.isEmpty()) return;
            for (Map<String, Object> row : rows) {
                String scope = row.get("scope") == null ? null : trimToNull(String.valueOf(row.get("scope")));
                String memoryType = row.get("memory_type") == null ? null : trimToNull(String.valueOf(row.get("memory_type")));
                if (scope == null || memoryType == null) continue;
                String factKey = deriveFactKey(scope, memoryType, String.valueOf(row.get("content")));
                jdbcTemplate.update(
                    "update ai_memory_item set fact_key = ?, updated_at = current_timestamp where id = ? and fact_key is null",
                    factKey,
                    row.get("id")
                );
            }
        }
    }

    private String normalizeComparableContent(String content) {
        return content == null ? "" : content.trim().replaceAll("\\s+", " ");
    }

    private String validateCompactJson(String rawJson, String fieldName) {
        String raw = trimToNull(rawJson);
        if (raw == null) return null;
        if (raw.length() > 20000) throw new BusinessException(ResultCode.BAD_REQUEST, fieldName + " is too large");
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            if (node == null || (!node.isObject() && !node.isArray())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, fieldName + " must be a JSON object or array");
            }
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, fieldName + " must be compact JSON");
        }
        return raw;
    }

    private String toJson(Map<String, Object> details) {
        try {
            return OBJECT_MAPPER.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "failed to serialize memory lifecycle audit");
        }
    }

    private Long insertReturningId(String tableName, List<String> columns, List<Object> values, String errorMessage) {
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into " + tableName + "(" + String.join(", ", columns) + ") values(" + placeholders + ")",
                new String[]{"id"}
            );
            for (int index = 0; index < values.size(); index++) ps.setObject(index + 1, values.get(index));
            return ps;
        }, keyHolder);
        for (Map<String, Object> keys : keyHolder.getKeyList()) {
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

    private String optionalSelectColumn(String column, boolean available) {
        return available ? ", " + column : ", null as " + column;
    }

    private <T> T inTransaction(Supplier<T> action) {
        T value = transactionTemplate.execute(status -> action.get());
        if (value == null) throw new BusinessException(ResultCode.INTERNAL_ERROR, "memory lifecycle transaction returned no result");
        return value;
    }

    private <T> T inCommittedTransaction(Supplier<T> action) {
        T value = committedTransactionTemplate.execute(status -> action.get());
        if (value == null) throw new BusinessException(ResultCode.INTERNAL_ERROR, "memory lifecycle transaction returned no result");
        return value;
    }

    private void addColumn(List<String> columns, List<Object> values, String column, Object value, boolean available) {
        if (!available) return;
        columns.add(column);
        values.add(value);
    }

    private String readString(ResultSet rs, String column, boolean available) throws SQLException {
        return available ? rs.getString(column) : null;
    }

    private Long readLong(ResultSet rs, String column, boolean available) throws SQLException {
        if (!available) return null;
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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

    private void requireUser(Long userId) {
        if (userId == null) throw new BusinessException(ResultCode.BAD_REQUEST, "userId is required");
    }

    private String requireText(String value, String message) {
        String text = trimToNull(value);
        if (text == null) throw new BusinessException(ResultCode.BAD_REQUEST, message);
        return text;
    }

    private String normalizeEnum(String value, Set<String> allowed, String message) {
        String normalized = requireText(value, message).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BusinessException(ResultCode.BAD_REQUEST, message);
        return normalized;
    }

    private double clampConfidence(Double confidence) {
        if (confidence == null) return 0.0d;
        return Math.max(0.0d, Math.min(1.0d, confidence));
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record StatusFilter(String sql, Object value) {}
    private record PromotionOutcome(AiMemoryVO memory, String conflictMessage) {}

    private static final class MemoryConflictDetected extends RuntimeException {
        private MemoryConflictDetected(String message) {
            super(message);
        }
    }

    public int markProjectScopeStale(Long userId, Long projectId, String reason) {
        if (userId == null || projectId == null) {
            return 0;
        }
        String sql = "update ai_memory_item set status = 'stale'";
        if (memoryLifecycleStatusColumnAvailable) {
            sql += ", lifecycle_status = 'STALE'";
        }
        if (memoryStaleAtColumnAvailable) {
            sql += ", stale_at = current_timestamp";
        }
        sql += ", updated_at = current_timestamp where user_id = ? and project_id = ? and (status in ('confirmed','active')";
        if (memoryLifecycleStatusColumnAvailable) {
            sql += " or lifecycle_status = 'CONFIRMED'";
        }
        sql += ")";
        return jdbcTemplate.update(sql, userId, projectId);
    }

}

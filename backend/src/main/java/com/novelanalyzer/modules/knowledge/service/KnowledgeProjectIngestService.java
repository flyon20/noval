package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.ProjectExtractionReviewRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectIngestSubmitRequest;
import com.novelanalyzer.modules.knowledge.vo.ProjectChapterVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectExtractionCandidateVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectWorkVO;
import com.novelanalyzer.modules.system.service.AgentResourcePressureService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeProjectIngestService {

    public static final String JOB_UPLOADED = "UPLOADED";
    public static final String JOB_PARSING = "PARSING";
    public static final String JOB_EXTRACTING = "EXTRACTING";
    public static final String JOB_INDEXING = "INDEXING";
    public static final String JOB_VERIFYING = "VERIFYING";
    public static final String JOB_READY = "READY";
    public static final String JOB_RETRYABLE_FAILED = "RETRYABLE_FAILED";
    public static final String JOB_TERMINAL_FAILED = "TERMINAL_FAILED";

    public static final String GEN_PREPARED = "PREPARED";
    public static final String GEN_STRUCTURED_READY = "STRUCTURED_READY";
    public static final String GEN_VECTOR_READY = "VECTOR_READY";
    public static final String GEN_VERIFYING = "VERIFYING";
    public static final String GEN_ACTIVE = "ACTIVE";
    public static final String GEN_RETIRED = "RETIRED";
    public static final String GEN_FAILED = "FAILED";

    private static final String ACTIVE_CANDIDATE_SELECT = """
        select c.*
        from ai_project_extraction_candidate c
        join ai_project_ingest_generation g
          on g.generation_id = c.generation_id
         and g.user_id = c.user_id
         and g.project_id = c.project_id
         and g.work_id = c.work_id
         and g.chapter_id = c.chapter_id
         and g.status = 'ACTIVE'
        join ai_project_chapter_head h
          on h.user_id = c.user_id
         and h.project_id = c.project_id
         and h.work_id = c.work_id
         and h.chapter_no = g.chapter_no
         and h.active_generation_id = c.generation_id
         and h.active_chapter_id = c.chapter_id
         and h.tombstoned_at is null
        """;

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectService projectService;
    private final KnowledgeProjectWorkService workService;
    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeProjectIngestQueueService queueService;
    private final AgentResourcePressureService resourcePressureService;
    private final KnowledgeMemoryService memoryService;

    public KnowledgeProjectIngestService(JdbcTemplate jdbcTemplate,
                                         KnowledgeProjectService projectService,
                                         KnowledgeProjectWorkService workService,
                                         KnowledgeProperties knowledgeProperties,
                                         ObjectProvider<KnowledgeProjectIngestQueueService> queueServiceProvider,
                                         ObjectProvider<AgentResourcePressureService> resourcePressureProvider,
                                         ObjectProvider<KnowledgeMemoryService> memoryServiceProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
        this.workService = workService;
        this.knowledgeProperties = knowledgeProperties == null ? new KnowledgeProperties() : knowledgeProperties;
        this.queueService = queueServiceProvider == null ? null : queueServiceProvider.getIfAvailable();
        this.resourcePressureService = resourcePressureProvider == null ? null : resourcePressureProvider.getIfAvailable();
        this.memoryService = memoryServiceProvider == null ? null : memoryServiceProvider.getIfAvailable();
    }


    @Transactional
    public ProjectIngestJobVO submit(Long projectId, Long workId, ProjectIngestSubmitRequest request) {
        AuthUser user = requireUser();
        ProjectWorkVO work = workService.findOwnedWorkPublic(projectId, workId, user.getUserId());
        KnowledgeProperties.ProjectIngest cfg = knowledgeProperties.getProjectIngest();
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "ingest request is required");
        }
        int chapterNo = request.getChapterNo() == null ? 0 : request.getChapterNo();
        if (chapterNo <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter no is required");
        }
        String content = requireText(request.getContent(), "chapter content is required", cfg.getMaxChapterChars());
        if (content.getBytes(StandardCharsets.UTF_8).length > cfg.getMaxFileBytes()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter content exceeds 20MB limit");
        }
        if (isTombstoned(user.getUserId(), projectId, workId, chapterNo)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chapter is tombstoned");
        }
        String parserVersionEarly = trimToNull(request.getParserVersion(), 64);
        if (parserVersionEarly == null) {
            parserVersionEarly = cfg.getParserVersion();
        }
        String contentHashEarly = sha256(content);
        String idempotencyKeyEarly = trimToNull(request.getIdempotencyKey(), 200);
        if (idempotencyKeyEarly == null) {
            idempotencyKeyEarly = "auto:" + workId + ":" + chapterNo + ":" + contentHashEarly + ":" + parserVersionEarly;
        }
        ProjectIngestJobVO existingEarly = findByIdempotency(user.getUserId(), idempotencyKeyEarly);
        if (existingEarly != null) {
            if (!contentHashEarly.equals(existingEarly.getContentHash())
                || !parserVersionEarly.equals(existingEarly.getParserVersion())
                || !workId.equals(existingEarly.getWorkId())
                || chapterNo != (existingEarly.getChapterNo() == null ? -1 : existingEarly.getChapterNo())) {
                throw new BusinessException(ResultCode.CONFLICT, "idempotency key reused with different parameters");
            }
            return existingEarly;
        }
        Integer chapterCount = jdbcTemplate.queryForObject(
            "select count(distinct chapter_no) from ai_project_chapter where project_id = ? and work_id = ? and status <> 'ARCHIVED'",
            Integer.class, projectId, workId);
        if (chapterCount != null && chapterCount >= cfg.getMaxChaptersPerProject()) {
            Integer existingChapter = jdbcTemplate.queryForObject(
                "select count(*) from ai_project_chapter where project_id = ? and work_id = ? and chapter_no = ? and status <> 'ARCHIVED'",
                Integer.class, projectId, workId, chapterNo);
            if (existingChapter == null || existingChapter == 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "project chapter quota exceeded");
            }
        }
        if (countActiveJobs(user.getUserId()) >= cfg.getMaxConcurrentJobsPerUser()) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "user already has an active ingest job");
        }
        if (resourcePressureService != null && resourcePressureService.shouldPauseIndexing()) {
            double disk = resourcePressureService.snapshot().diskUsedPercent();
            if (disk >= 0 && disk >= knowledgeProperties.getResourcePolicy().getDiskStopImportPercent()) {
                throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "disk pressure rejects ingest");
            }
        }
        String parserVersion = trimToNull(request.getParserVersion(), 64);
        if (parserVersion == null) {
            parserVersion = cfg.getParserVersion();
        }
        String contentHash = sha256(content);
        String idempotencyKey = trimToNull(request.getIdempotencyKey(), 200);
        if (idempotencyKey == null) {
            idempotencyKey = "auto:" + workId + ":" + chapterNo + ":" + contentHash + ":" + parserVersion;
        }
        ProjectIngestJobVO existing = findByIdempotency(user.getUserId(), idempotencyKey);
        if (existing != null) {
            if (!contentHash.equals(existing.getContentHash())
                || !parserVersion.equals(existing.getParserVersion())
                || !workId.equals(existing.getWorkId())
                || chapterNo != (existing.getChapterNo() == null ? -1 : existing.getChapterNo())) {
                throw new BusinessException(ResultCode.CONFLICT, "idempotency key reused with different parameters");
            }
            return existing;
        }
        String sourceType = trimToNull(request.getSourceType(), 40);
        if (sourceType == null) {
            sourceType = "upload";
        }
        String title = trimToNull(request.getTitle(), 200);
        int nextVersion = nextChapterVersion(workId, chapterNo);
        KeyHolder chapterKey = new GeneratedKeyHolder();
        String finalSourceType = sourceType;
        String finalParserVersion = parserVersion;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_chapter(user_id, project_id, work_id, chapter_no, title, content, content_hash, word_count, source_type, version, status) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED')",
                new String[]{"chapter_id"});
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setLong(3, workId);
            statement.setInt(4, chapterNo);
            statement.setString(5, title);
            statement.setString(6, content);
            statement.setString(7, contentHash);
            statement.setInt(8, content.length());
            statement.setString(9, finalSourceType);
            statement.setInt(10, nextVersion);
            return statement;
        }, chapterKey);
        Number chapterIdNum = chapterKey.getKey();
        if (chapterIdNum == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "chapter id missing");
        }
        long chapterId = chapterIdNum.longValue();
        KeyHolder genKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_ingest_generation(user_id, project_id, work_id, chapter_id, chapter_no, chapter_version, content_hash, parser_version, status) values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new String[]{"generation_id"});
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setLong(3, workId);
            statement.setLong(4, chapterId);
            statement.setInt(5, chapterNo);
            statement.setInt(6, nextVersion);
            statement.setString(7, contentHash);
            statement.setString(8, finalParserVersion);
            statement.setString(9, GEN_PREPARED);
            return statement;
        }, genKey);
        Number genIdNum = genKey.getKey();
        if (genIdNum == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "generation id missing");
        }
        long generationId = genIdNum.longValue();
        ensureChapterHead(user.getUserId(), projectId, workId, chapterNo);
        final String finalIdempotencyKey = idempotencyKey;
        final String finalTitle = title;
        final long finalChapterId = chapterId;
        final long finalGenerationId = generationId;
        final int finalChapterNo = chapterNo;
        final String finalContentHash = contentHash;
        final int finalMaxAttempts = Math.max(1, cfg.getMaxAttempts());
        KeyHolder jobKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_ingest_job(user_id, project_id, work_id, chapter_id, idempotency_key, generation_id, chapter_no, content_hash, parser_version, attempt, max_attempts, fencing_token, queue_published_attempt, stage, title, source_type, job_type, status, progress) values(?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, 0, ?, ?, ?, 'chapter_import_parse', ?, 0)",
                new String[]{"ingest_job_id"});
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setLong(3, workId);
            statement.setLong(4, finalChapterId);
            statement.setString(5, finalIdempotencyKey);
            statement.setLong(6, finalGenerationId);
            statement.setInt(7, finalChapterNo);
            statement.setString(8, finalContentHash);
            statement.setString(9, finalParserVersion);
            statement.setInt(10, finalMaxAttempts);
            statement.setString(11, JOB_UPLOADED);
            statement.setString(12, finalTitle);
            statement.setString(13, finalSourceType);
            statement.setString(14, JOB_UPLOADED);
            return statement;
        }, jobKey);
        Number jobIdNum = jobKey.getKey();
        if (jobIdNum == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "ingest job id missing");
        }
        long ingestJobId = jobIdNum.longValue();
        jdbcTemplate.update(
            "insert into ai_project_ingest_outbox(ingest_job_id, event_type, attempt, payload, status, available_at) values(?, 'EXECUTE', 1, ?, 'PENDING', current_timestamp)",
            ingestJobId, "{\"ingestJobId\":" + ingestJobId + ",\"attempt\":1}");
        ProjectIngestJobVO job = getOwnedJob(user.getUserId(), ingestJobId);
        dispatchAfterCommit(ingestJobId, 1);
        return job;
    }


    public ProjectIngestJobVO getJob(Long projectId, Long ingestJobId) {
        AuthUser user = requireUser();
        ProjectIngestJobVO job = getOwnedJob(user.getUserId(), ingestJobId);
        if (!projectId.equals(job.getProjectId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "ingest job not found");
        }
        return job;
    }

    public List<ProjectIngestJobVO> listJobs(Long projectId, Long workId, int limit) {
        AuthUser user = requireUser();
        projectService.ensureOwned(projectId, user.getUserId());
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (workId == null) {
            return jdbcTemplate.query(
                "select * from ai_project_ingest_job where user_id = ? and project_id = ? order by ingest_job_id desc limit ?",
                jobMapper(), user.getUserId(), projectId, safeLimit).stream().map(this::withLabel).toList();
        }
        return jdbcTemplate.query(
            "select * from ai_project_ingest_job where user_id = ? and project_id = ? and work_id = ? order by ingest_job_id desc limit ?",
            jobMapper(), user.getUserId(), projectId, workId, safeLimit).stream().map(this::withLabel).toList();
    }

    @Transactional
    public ProjectIngestJobVO retry(Long projectId, Long ingestJobId) {
        AuthUser user = requireUser();
        ProjectIngestJobVO job = getOwnedJob(user.getUserId(), ingestJobId);
        if (!projectId.equals(job.getProjectId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "ingest job not found");
        }
        if (!JOB_TERMINAL_FAILED.equals(job.getStatus()) && !JOB_RETRYABLE_FAILED.equals(job.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "only failed jobs can be retried");
        }
        int nextAttempt = (job.getAttempt() == null ? 1 : job.getAttempt()) + 1;
        int nextMaxAttempts = Math.max(job.getMaxAttempts() == null ? 1 : job.getMaxAttempts(), nextAttempt);
        if (job.getGenerationId() != null) {
            int generationReset = jdbcTemplate.update(
                "update ai_project_ingest_generation set status = ?, error_code = null, error_summary = null, "
                    + "cleanup_status = null, cleanup_error = null, updated_at = current_timestamp "
                    + "where generation_id = ? and status in (?, ?) "
                    + "and (cleanup_status is null or cleanup_status <> 'RUNNING')",
                GEN_PREPARED, job.getGenerationId(), GEN_FAILED, GEN_PREPARED);
            if (generationReset != 1) {
                throw new BusinessException(ResultCode.CONFLICT, "generation cleanup or state rejected retry");
            }
        }
        int updated = jdbcTemplate.update(
            "update ai_project_ingest_job set status = ?, stage = ?, attempt = ?, max_attempts = ?, progress = 0, error_code = null, error_summary = null, next_retry_at = null, lease_owner = null, lease_expires_at = null, heartbeat_at = null, fencing_token = fencing_token + 1, queue_published_attempt = 0, updated_at = current_timestamp where ingest_job_id = ? and user_id = ? and status in (?, ?)",
            JOB_UPLOADED, JOB_UPLOADED, nextAttempt, nextMaxAttempts, ingestJobId, user.getUserId(), JOB_TERMINAL_FAILED, JOB_RETRYABLE_FAILED);
        if (updated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "retry rejected by CAS");
        }
        String payload = "{\"ingestJobId\":" + ingestJobId + ",\"attempt\":" + nextAttempt + "}";
        int resumed = jdbcTemplate.update(
            "update ai_project_ingest_outbox set payload = ?, status = 'PENDING', available_at = current_timestamp, published_at = null, last_error = null, updated_at = current_timestamp where ingest_job_id = ? and event_type = 'EXECUTE' and attempt = ?",
            payload, ingestJobId, nextAttempt);
        if (resumed == 0) {
            jdbcTemplate.update(
                "insert into ai_project_ingest_outbox(ingest_job_id, event_type, attempt, payload, status, available_at) values(?, 'EXECUTE', ?, ?, 'PENDING', current_timestamp)",
                ingestJobId, nextAttempt, payload);
        }
        ProjectIngestJobVO refreshed = getOwnedJob(user.getUserId(), ingestJobId);
        dispatchAfterCommit(ingestJobId, nextAttempt);
        return refreshed;
    }

    public List<ProjectExtractionCandidateVO> listCandidates(Long projectId, Long workId, String status, int limit) {
        AuthUser user = requireUser();
        projectService.ensureOwned(projectId, user.getUserId());
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String normalized = trimToNull(status, 30);
        StringBuilder sql = new StringBuilder(ACTIVE_CANDIDATE_SELECT)
            .append(" where c.user_id = ? and c.project_id = ?");
        List<Object> args = new ArrayList<>(List.of(user.getUserId(), projectId));
        if (workId != null) {
            sql.append(" and c.work_id = ?");
            args.add(workId);
        }
        if (normalized != null) {
            sql.append(" and c.status = ?");
            args.add(normalized);
        }
        sql.append(" order by c.candidate_id desc limit ?");
        args.add(safeLimit);
        return jdbcTemplate.query(sql.toString(), candidateMapper(), args.toArray());
    }

    @Transactional
    public ProjectExtractionCandidateVO reviewCandidate(Long projectId, Long candidateId, ProjectExtractionReviewRequest request) {
        AuthUser user = requireUser();
        ProjectExtractionCandidateVO candidate = findActiveCandidate(candidateId, user.getUserId(), projectId);
        String decision = request == null ? null : trimToNull(request.getDecision(), 30);
        if (decision == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "decision is required");
        }
        String upper = decision.toUpperCase(Locale.ROOT);
        if (!Set.of("CONFIRMED", "REJECTED", "SUPERSEDED").contains(upper)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported decision");
        }
        String payload = request.getPayloadJson();
        if (payload == null || payload.isBlank()) {
            payload = candidate.getPayloadJson();
        }
        int updated = jdbcTemplate.update(
            """
                update ai_project_extraction_candidate
                set status = ?, payload = ?, review_note = ?, reviewed_by = ?,
                    reviewed_at = current_timestamp, updated_at = current_timestamp
                where candidate_id = ? and user_id = ? and project_id = ? and work_id = ?
                  and generation_id = ? and chapter_id = ? and status = 'PENDING'
                  and exists (
                    select 1
                    from ai_project_ingest_generation g
                    join ai_project_chapter_head h
                      on h.user_id = g.user_id
                     and h.project_id = g.project_id
                     and h.work_id = g.work_id
                     and h.chapter_no = g.chapter_no
                     and h.active_generation_id = g.generation_id
                     and h.active_chapter_id = g.chapter_id
                     and h.tombstoned_at is null
                    where g.generation_id = ? and g.user_id = ? and g.project_id = ?
                      and g.work_id = ? and g.chapter_id = ? and g.status = 'ACTIVE'
                  )
                """,
            upper, payload, trimToNull(request.getReviewNote(), 500), user.getUserId(),
            candidateId, user.getUserId(), projectId, candidate.getWorkId(), candidate.getGenerationId(),
            candidate.getChapterId(), candidate.getGenerationId(), user.getUserId(), projectId,
            candidate.getWorkId(), candidate.getChapterId());
        if (updated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "candidate is not pending");
        }
        return findActiveCandidate(candidateId, user.getUserId(), projectId);
    }

    private ProjectExtractionCandidateVO findActiveCandidate(Long candidateId, Long userId, Long projectId) {
        return jdbcTemplate.query(
                ACTIVE_CANDIDATE_SELECT + " where c.candidate_id = ? and c.user_id = ? and c.project_id = ?",
                candidateMapper(), candidateId, userId, projectId)
            .stream().findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "candidate not found"));
    }

    @Transactional
    public void tombstoneChapter(Long projectId, Long workId, int chapterNo) {
        AuthUser user = requireUser();
        workService.findOwnedWorkPublic(projectId, workId, user.getUserId());
        Instant alertAfter = Instant.now().plus(Duration.ofHours(24));
        Integer headCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_chapter_head where user_id = ? and project_id = ? and work_id = ? and chapter_no = ?",
            Integer.class, user.getUserId(), projectId, workId, chapterNo);
        if (headCount == null || headCount == 0) {
            jdbcTemplate.update(
                "insert into ai_project_chapter_head(user_id, project_id, work_id, chapter_no, optimistic_version, tombstoned_at) values(?, ?, ?, ?, 0, current_timestamp)",
                user.getUserId(), projectId, workId, chapterNo);
        } else {
            jdbcTemplate.update(
                "update ai_project_chapter_head set tombstoned_at = current_timestamp, active_generation_id = null, updated_at = current_timestamp where user_id = ? and project_id = ? and work_id = ? and chapter_no = ?",
                user.getUserId(), projectId, workId, chapterNo);
        }
        jdbcTemplate.update(
            "insert into ai_project_tombstone(user_id, project_id, work_id, chapter_no, scope_type, cleanup_stage, alert_after_at) values(?, ?, ?, ?, 'CHAPTER', 'QUEUED', ?)",
            user.getUserId(), projectId, workId, chapterNo, Timestamp.from(alertAfter));
        if (memoryService != null) {
            memoryService.markProjectScopeStale(user.getUserId(), projectId, "chapter tombstone");
        }
    }


    public boolean transitionJob(Long ingestJobId, String expectedStatus, String nextStatus, String stage,
                                 Integer progress, String leaseOwner, long fencingToken) {
        int updated = jdbcTemplate.update(
            "update ai_project_ingest_job set status = ?, stage = ?, progress = coalesce(?, progress), heartbeat_at = current_timestamp, updated_at = current_timestamp where ingest_job_id = ? and status = ? and fencing_token = ? and (lease_owner is null or lease_owner = ?)",
            nextStatus, stage, progress, ingestJobId, expectedStatus, fencingToken, leaseOwner);
        return updated == 1;
    }

    public boolean transitionGeneration(Long generationId, String expectedStatus, String nextStatus) {
        int updated = jdbcTemplate.update(
            "update ai_project_ingest_generation set status = ?, updated_at = current_timestamp where generation_id = ? and status = ?",
            nextStatus, generationId, expectedStatus);
        return updated == 1;
    }

    public boolean transitionGeneration(Long ingestJobId,
                                        String leaseOwner,
                                        long fencingToken,
                                        Long generationId,
                                        String expectedStatus,
                                        String nextStatus) {
        int updated = jdbcTemplate.update(
            """
                update ai_project_ingest_generation
                set status = ?, updated_at = current_timestamp
                where generation_id = ? and status = ?
                  and exists (
                    select 1 from ai_project_ingest_job j
                    where j.ingest_job_id = ? and j.generation_id = ?
                      and j.lease_owner = ? and j.fencing_token = ?
                      and j.lease_expires_at >= current_timestamp
                  )
                """,
            nextStatus, generationId, expectedStatus,
            ingestJobId, generationId, leaseOwner, fencingToken);
        return updated == 1;
    }

    public boolean resetGenerationForExecution(Long ingestJobId,
                                               String leaseOwner,
                                               long fencingToken,
                                               Long generationId) {
        int updated = jdbcTemplate.update(
            """
                update ai_project_ingest_generation
                set status = ?, scene_count = 0, vector_count = 0, entity_count = 0,
                    expected_scene_count = null, expected_vector_count = null, expected_entity_count = null,
                    error_code = null, error_summary = null, cleanup_status = null, cleanup_error = null,
                    updated_at = current_timestamp
                where generation_id = ? and status <> ?
                  and exists (
                    select 1 from ai_project_ingest_job j
                    where j.ingest_job_id = ? and j.generation_id = ?
                      and j.lease_owner = ? and j.fencing_token = ?
                      and j.lease_expires_at >= current_timestamp
                  )
                """,
            GEN_PREPARED, generationId, GEN_ACTIVE,
            ingestJobId, generationId, leaseOwner, fencingToken);
        return updated == 1;
    }

    public boolean recordGenerationCounts(Long ingestJobId,
                                          String leaseOwner,
                                          long fencingToken,
                                          Long generationId,
                                          KnowledgeProjectWorkService.ArtifactCounts counts) {
        int updated = jdbcTemplate.update(
            """
                update ai_project_ingest_generation
                set scene_count = ?, vector_count = ?, entity_count = ?,
                    expected_scene_count = ?, expected_vector_count = ?, expected_entity_count = ?,
                    updated_at = current_timestamp
                where generation_id = ?
                  and exists (
                    select 1 from ai_project_ingest_job j
                    where j.ingest_job_id = ? and j.generation_id = ?
                      and j.status = ? and j.lease_owner = ? and j.fencing_token = ?
                      and j.lease_expires_at >= current_timestamp
                  )
                """,
            counts.sceneCount(), counts.vectorCount(), counts.entityCount(),
            counts.sceneCount(), counts.vectorCount(), counts.entityCount(),
            generationId, ingestJobId, generationId, JOB_VERIFYING, leaseOwner, fencingToken);
        return updated == 1;
    }

    @Transactional
    public boolean activateGeneration(Long generationId, Long userId, Long projectId, Long workId, int chapterNo, Long chapterId) {
        return activateGenerationInternal(generationId, userId, projectId, workId, chapterNo, chapterId);
    }

    @Transactional
    public boolean activateGenerationAndCompleteJob(Long ingestJobId,
                                                    String leaseOwner,
                                                    long fencingToken,
                                                    Long generationId,
                                                    Long userId,
                                                    Long projectId,
                                                    Long workId,
                                                    int chapterNo,
                                                    Long chapterId) {
        List<Long> ownedJobs = jdbcTemplate.query(
            "select ingest_job_id from ai_project_ingest_job where ingest_job_id = ? and generation_id = ? and status = ? and lease_owner = ? and fencing_token = ? and lease_expires_at >= current_timestamp for update",
            (rs, rowNum) -> rs.getLong(1),
            ingestJobId, generationId, JOB_VERIFYING, leaseOwner, fencingToken);
        if (ownedJobs.size() != 1) {
            return false;
        }
        if (!activateGenerationInternal(generationId, userId, projectId, workId, chapterNo, chapterId)) {
            return false;
        }
        int completed = jdbcTemplate.update(
            "update ai_project_ingest_job set status = ?, stage = ?, progress = 100, next_retry_at = null, lease_owner = null, lease_expires_at = null, heartbeat_at = current_timestamp, error_code = null, error_summary = null, updated_at = current_timestamp where ingest_job_id = ? and generation_id = ? and status = ? and lease_owner = ? and fencing_token = ?",
            JOB_READY, JOB_READY, ingestJobId, generationId, JOB_VERIFYING, leaseOwner, fencingToken);
        if (completed != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "ingest completion CAS rejected");
        }
        return true;
    }

    private boolean activateGenerationInternal(Long generationId,
                                               Long userId,
                                               Long projectId,
                                               Long workId,
                                               int chapterNo,
                                               Long chapterId) {
        if (isTombstoned(userId, projectId, workId, chapterNo)) {
            return false;
        }
        List<Long> previousRows = jdbcTemplate.query(
            "select active_generation_id from ai_project_chapter_head where user_id = ? and project_id = ? and work_id = ? and chapter_no = ? for update",
            (rs, rowNum) -> rs.getObject("active_generation_id") == null ? null : rs.getLong("active_generation_id"),
            userId, projectId, workId, chapterNo);
        Long previous = previousRows.isEmpty() ? null : previousRows.get(0);
        int headUpdated = jdbcTemplate.update(
            "update ai_project_chapter_head set active_chapter_id = ?, active_generation_id = ?, optimistic_version = optimistic_version + 1, updated_at = current_timestamp where user_id = ? and project_id = ? and work_id = ? and chapter_no = ? and tombstoned_at is null",
            chapterId, generationId, userId, projectId, workId, chapterNo);
        if (headUpdated != 1) {
            return false;
        }
        int genUpdated = jdbcTemplate.update(
            "update ai_project_ingest_generation set status = ?, activated_at = current_timestamp, updated_at = current_timestamp where generation_id = ? and status = ?",
            GEN_ACTIVE, generationId, GEN_VERIFYING);
        if (genUpdated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "generation activation CAS rejected");
        }
        if (previous != null && !previous.equals(generationId)) {
            jdbcTemplate.update(
                "update ai_project_ingest_generation set status = ?, retired_at = current_timestamp, cleanup_status = 'QUEUED', updated_at = current_timestamp where generation_id = ? and status = ?",
                GEN_RETIRED, previous, GEN_ACTIVE);
            jdbcTemplate.update("update ai_project_scene set status = 'RETIRED' where generation_id = ?", previous);
            jdbcTemplate.update("update ai_project_vector_chunk set status = 'RETIRED' where generation_id = ?", previous);
        }
        jdbcTemplate.update("update ai_project_chapter set status = 'ACTIVE', updated_at = current_timestamp where chapter_id = ?", chapterId);
        if (memoryService != null) {
            memoryService.markProjectScopeStale(userId, projectId, "generation activated");
        }
        return true;
    }

    public ClaimResult claimJob(Long ingestJobId, String leaseOwner, Duration leaseDuration) {
        return claimJob(ingestJobId, null, leaseOwner, leaseDuration);
    }

    public ClaimResult claimJob(Long ingestJobId, Integer expectedAttempt, String leaseOwner, Duration leaseDuration) {
        Timestamp expires = Timestamp.from(Instant.now().plus(leaseDuration));
        String attemptClause = expectedAttempt == null ? "" : " and attempt = ?";
        List<Object> args = new java.util.ArrayList<>(List.of(
            leaseOwner, expires, ingestJobId,
            JOB_UPLOADED, JOB_PARSING, JOB_EXTRACTING, JOB_INDEXING, JOB_VERIFYING,
            leaseOwner
        ));
        if (expectedAttempt != null) {
            args.add(expectedAttempt);
        }
        int updated = jdbcTemplate.update(
            "update ai_project_ingest_job set lease_owner = ?, lease_expires_at = ?, heartbeat_at = current_timestamp, fencing_token = fencing_token + 1, updated_at = current_timestamp where ingest_job_id = ? and status in (?, ?, ?, ?, ?) and (lease_owner is null or lease_owner = ? or lease_expires_at is null or lease_expires_at < current_timestamp)" + attemptClause,
            args.toArray());
        if (updated != 1) {
            return null;
        }
        ProjectIngestJobVO job = jdbcTemplate.query("select * from ai_project_ingest_job where ingest_job_id = ?", jobMapper(), ingestJobId)
            .stream().findFirst().orElse(null);
        if (job == null) {
            return null;
        }
        return new ClaimResult(withLabel(job), job.getFencingToken() == null ? 0L : job.getFencingToken());
    }

    public boolean heartbeat(Long ingestJobId, String leaseOwner, long fencingToken, Duration leaseDuration) {
        Timestamp expires = Timestamp.from(Instant.now().plus(leaseDuration));
        int updated = jdbcTemplate.update(
            "update ai_project_ingest_job set lease_expires_at = ?, heartbeat_at = current_timestamp, updated_at = current_timestamp where ingest_job_id = ? and lease_owner = ? and fencing_token = ?",
            expires, ingestJobId, leaseOwner, fencingToken);
        return updated == 1;
    }

    public void markJobFailed(Long ingestJobId, String leaseOwner, long fencingToken, String errorCode, String errorSummary, boolean terminal) {
        jdbcTemplate.update(
            "update ai_project_ingest_job set status = ?, error_code = ?, error_summary = ?, next_retry_at = null, lease_owner = null, lease_expires_at = null, updated_at = current_timestamp where ingest_job_id = ? and fencing_token = ? and (lease_owner is null or lease_owner = ?)",
            terminal ? JOB_TERMINAL_FAILED : JOB_RETRYABLE_FAILED, trimToNull(errorCode, 64), trimToNull(errorSummary, 500),
            ingestJobId, fencingToken, leaseOwner);
    }

    @Transactional
    public FailureDisposition failAndScheduleRetry(Long ingestJobId,
                                                   String leaseOwner,
                                                   long fencingToken,
                                                   String errorCode,
                                                   String errorSummary,
                                                   boolean retryable) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select attempt, max_attempts, generation_id from ai_project_ingest_job where ingest_job_id = ?",
            ingestJobId);
        if (rows.isEmpty()) {
            return FailureDisposition.STALE;
        }
        Map<String, Object> row = rows.get(0);
        int attempt = ((Number) row.getOrDefault("attempt", 1)).intValue();
        int maxAttempts = ((Number) row.getOrDefault("max_attempts", knowledgeProperties.getProjectIngest().getMaxAttempts())).intValue();
        boolean terminal = !retryable || attempt >= Math.max(1, maxAttempts);
        int nextAttempt = attempt + 1;
        Instant nextRetryAt = terminal ? null : Instant.now().plusSeconds(retryBackoffSeconds(nextAttempt));
        int updated = jdbcTemplate.update(
            "update ai_project_ingest_job set status = ?, error_code = ?, error_summary = ?, next_retry_at = ?, lease_owner = null, lease_expires_at = null, heartbeat_at = null, updated_at = current_timestamp where ingest_job_id = ? and fencing_token = ? and lease_owner = ?",
            terminal ? JOB_TERMINAL_FAILED : JOB_RETRYABLE_FAILED,
            trimToNull(errorCode, 64), trimToNull(errorSummary, 500),
            nextRetryAt == null ? null : Timestamp.from(nextRetryAt),
            ingestJobId, fencingToken, leaseOwner);
        if (updated != 1) {
            return FailureDisposition.STALE;
        }
        Long generationId = row.get("generation_id") instanceof Number number ? number.longValue() : null;
        if (terminal && generationId != null) {
            jdbcTemplate.update(
                "update ai_project_ingest_generation set status = ?, error_code = ?, error_summary = ?, cleanup_status = 'QUEUED', updated_at = current_timestamp where generation_id = ? and status <> ?",
                GEN_FAILED, trimToNull(errorCode, 64), trimToNull(errorSummary, 500), generationId, GEN_ACTIVE);
            return FailureDisposition.TERMINAL;
        }
        if (!terminal) {
            Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from ai_project_ingest_outbox where ingest_job_id = ? and event_type = 'EXECUTE' and attempt = ?",
                Integer.class, ingestJobId, nextAttempt);
            if (existing == null || existing == 0) {
                jdbcTemplate.update(
                    "insert into ai_project_ingest_outbox(ingest_job_id, event_type, attempt, payload, status, available_at) values(?, 'EXECUTE', ?, ?, 'PENDING', ?)",
                    ingestJobId, nextAttempt,
                    "{\"ingestJobId\":" + ingestJobId + ",\"attempt\":" + nextAttempt + "}",
                    Timestamp.from(nextRetryAt));
            }
            return FailureDisposition.RETRY_SCHEDULED;
        }
        return FailureDisposition.TERMINAL;
    }

    public boolean markJobReady(Long ingestJobId, String leaseOwner, long fencingToken) {
        return jdbcTemplate.update(
            "update ai_project_ingest_job set status = ?, stage = ?, progress = 100, next_retry_at = null, lease_owner = null, lease_expires_at = null, error_code = null, error_summary = null, updated_at = current_timestamp where ingest_job_id = ? and fencing_token = ? and lease_owner = ?",
            JOB_READY, JOB_READY, ingestJobId, fencingToken, leaseOwner) == 1;
    }

    public ProjectIngestJobVO getOwnedJob(Long userId, Long ingestJobId) {
        return jdbcTemplate.query("select * from ai_project_ingest_job where ingest_job_id = ? and user_id = ?",
            jobMapper(), ingestJobId, userId).stream().map(this::withLabel).findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "ingest job not found"));
    }

    public ProjectIngestJobVO getJobById(Long ingestJobId) {
        return jdbcTemplate.query("select * from ai_project_ingest_job where ingest_job_id = ?",
            jobMapper(), ingestJobId).stream().map(this::withLabel).findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "ingest job not found"));
    }

    public ProjectChapterVO loadChapter(Long chapterId) {
        return jdbcTemplate.query(
            "select chapter_id, user_id, project_id, work_id, chapter_no, title, content, content_hash, word_count, source_type, version, status, created_at, updated_at from ai_project_chapter where chapter_id = ?",
            workService.chapterMapperPublic(), chapterId).stream().findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "chapter not found"));
    }


    public int dispatchPendingOutbox(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Timestamp staleBefore = Timestamp.from(Instant.now().minusSeconds(30));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
                select outbox_id, ingest_job_id, attempt
                from ai_project_ingest_outbox
                where event_type = 'EXECUTE' and (
                  (status = 'PENDING' and available_at <= current_timestamp)
                  or (status = 'DISPATCHING' and updated_at < ?)
                )
                order by outbox_id asc
                limit ?
                """,
            staleBefore, safeLimit);
        int dispatched = 0;
        for (Map<String, Object> row : rows) {
            long outboxId = ((Number) row.get("outbox_id")).longValue();
            long ingestJobId = ((Number) row.get("ingest_job_id")).longValue();
            int attempt = ((Number) row.get("attempt")).intValue();
            if (dispatchOutbox(outboxId, ingestJobId, attempt, staleBefore)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    public int recoverExpiredJobs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
                select ingest_job_id, lease_owner, fencing_token
                from ai_project_ingest_job
                where status in ('PARSING', 'EXTRACTING', 'INDEXING', 'VERIFYING')
                  and lease_owner is not null and lease_expires_at < current_timestamp
                order by updated_at asc
                limit ?
                """,
            safeLimit);
        int recovered = 0;
        for (Map<String, Object> row : rows) {
            FailureDisposition disposition = failAndScheduleRetry(
                ((Number) row.get("ingest_job_id")).longValue(),
                String.valueOf(row.get("lease_owner")),
                ((Number) row.get("fencing_token")).longValue(),
                "LEASE_EXPIRED",
                "project ingest worker lease expired",
                true);
            if (disposition != FailureDisposition.STALE) {
                recovered++;
            }
        }
        return recovered;
    }

    @Transactional
    public boolean scheduleVectorRepair(Long activeGenerationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
                select g.user_id, g.project_id, g.work_id, g.chapter_id, g.chapter_no,
                       g.chapter_version, g.content_hash, g.parser_version,
                       c.title, c.source_type
                from ai_project_ingest_generation g
                join ai_project_chapter_head h on h.user_id = g.user_id
                  and h.project_id = g.project_id and h.work_id = g.work_id
                  and h.chapter_no = g.chapter_no and h.active_generation_id = g.generation_id
                join ai_project_chapter c on c.chapter_id = g.chapter_id
                where g.generation_id = ? and g.status = ? and h.tombstoned_at is null
                  and g.expected_vector_count is not null
                  and g.vector_count < g.expected_vector_count
                """,
            activeGenerationId, GEN_ACTIVE);
        if (rows.isEmpty()) {
            return false;
        }
        String idempotencyKey = "repair-vector:" + activeGenerationId;
        Integer existing = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_ingest_job where user_id = ? and idempotency_key = ?",
            Integer.class, rows.get(0).get("user_id"), idempotencyKey);
        if (existing != null && existing > 0) {
            return false;
        }

        Map<String, Object> row = rows.get(0);
        KeyHolder generationKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_ingest_generation(user_id, project_id, work_id, chapter_id, chapter_no, chapter_version, content_hash, parser_version, status) values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new String[]{"generation_id"});
            statement.setLong(1, ((Number) row.get("user_id")).longValue());
            statement.setLong(2, ((Number) row.get("project_id")).longValue());
            statement.setLong(3, ((Number) row.get("work_id")).longValue());
            statement.setLong(4, ((Number) row.get("chapter_id")).longValue());
            statement.setInt(5, ((Number) row.get("chapter_no")).intValue());
            statement.setInt(6, ((Number) row.get("chapter_version")).intValue());
            statement.setString(7, String.valueOf(row.get("content_hash")));
            statement.setString(8, String.valueOf(row.get("parser_version")));
            statement.setString(9, GEN_PREPARED);
            return statement;
        }, generationKey);
        Number generationId = generationKey.getKey();
        if (generationId == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "repair generation id missing");
        }

        KeyHolder jobKey = new GeneratedKeyHolder();
        int maxAttempts = Math.max(1, knowledgeProperties.getProjectIngest().getMaxAttempts());
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_ingest_job(user_id, project_id, work_id, chapter_id, idempotency_key, generation_id, chapter_no, content_hash, parser_version, attempt, max_attempts, fencing_token, queue_published_attempt, stage, title, source_type, job_type, status, progress) values(?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, 0, ?, ?, ?, 'chapter_import_parse', ?, 0)",
                new String[]{"ingest_job_id"});
            statement.setLong(1, ((Number) row.get("user_id")).longValue());
            statement.setLong(2, ((Number) row.get("project_id")).longValue());
            statement.setLong(3, ((Number) row.get("work_id")).longValue());
            statement.setLong(4, ((Number) row.get("chapter_id")).longValue());
            statement.setString(5, idempotencyKey);
            statement.setLong(6, generationId.longValue());
            statement.setInt(7, ((Number) row.get("chapter_no")).intValue());
            statement.setString(8, String.valueOf(row.get("content_hash")));
            statement.setString(9, String.valueOf(row.get("parser_version")));
            statement.setInt(10, maxAttempts);
            statement.setString(11, JOB_UPLOADED);
            statement.setString(12, row.get("title") == null ? null : String.valueOf(row.get("title")));
            statement.setString(13, row.get("source_type") == null ? "repair" : String.valueOf(row.get("source_type")));
            statement.setString(14, JOB_UPLOADED);
            return statement;
        }, jobKey);
        Number ingestJobId = jobKey.getKey();
        if (ingestJobId == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "repair ingest job id missing");
        }
        jdbcTemplate.update(
            "insert into ai_project_ingest_outbox(ingest_job_id, event_type, attempt, payload, status, available_at) values(?, 'EXECUTE', 1, ?, 'PENDING', current_timestamp)",
            ingestJobId.longValue(),
            "{\"ingestJobId\":" + ingestJobId.longValue() + ",\"attempt\":1}");
        dispatchAfterCommit(ingestJobId.longValue(), 1);
        return true;
    }

    private void dispatchAfterCommit(long ingestJobId, int attempt) {
        Runnable dispatch = () -> dispatchOutboxForJobAttempt(ingestJobId, attempt);
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
            return;
        }
        dispatch.run();
    }

    private boolean dispatchOutboxForJobAttempt(long ingestJobId, int attempt) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select outbox_id from ai_project_ingest_outbox where ingest_job_id = ? and event_type = 'EXECUTE' and attempt = ? and status in ('PENDING', 'DISPATCHING') order by outbox_id asc limit 1",
            ingestJobId, attempt);
        if (rows.isEmpty()) {
            return false;
        }
        return dispatchOutbox(
            ((Number) rows.get(0).get("outbox_id")).longValue(),
            ingestJobId,
            attempt,
            Timestamp.from(Instant.now().minusSeconds(30)));
    }

    private boolean dispatchOutbox(long outboxId, long ingestJobId, int attempt, Timestamp staleBefore) {
        if (queueService == null || !knowledgeProperties.getProjectIngest().isQueueEnabled()) {
            return false;
        }
        int claimed = jdbcTemplate.update(
            "update ai_project_ingest_outbox set status = 'DISPATCHING', attempt_count = attempt_count + 1, last_error = null, updated_at = current_timestamp where outbox_id = ? and ((status = 'PENDING' and available_at <= current_timestamp) or (status = 'DISPATCHING' and updated_at < ?))",
            outboxId, staleBefore);
        if (claimed != 1) {
            return false;
        }
        if (!prepareJobForDispatch(ingestJobId, attempt)) {
            jdbcTemplate.update(
                "update ai_project_ingest_outbox set status = 'DEAD', last_error = 'job state rejected dispatch', updated_at = current_timestamp where outbox_id = ? and status = 'DISPATCHING'",
                outboxId);
            return false;
        }
        boolean published = queueService.publishExecute(ingestJobId, attempt);
        if (!published) {
            jdbcTemplate.update(
                "update ai_project_ingest_outbox set status = 'PENDING', available_at = ?, last_error = 'rabbit publish not confirmed', updated_at = current_timestamp where outbox_id = ? and status = 'DISPATCHING'",
                Timestamp.from(Instant.now().plusSeconds(5)), outboxId);
            return false;
        }
        int completed = jdbcTemplate.update(
            "update ai_project_ingest_outbox set status = 'PUBLISHED', published_at = current_timestamp, last_error = null, updated_at = current_timestamp where outbox_id = ? and status = 'DISPATCHING'",
            outboxId);
        if (completed == 1) {
            jdbcTemplate.update(
                "update ai_project_ingest_job set queue_published_attempt = ?, updated_at = current_timestamp where ingest_job_id = ? and attempt = ?",
                attempt, ingestJobId, attempt);
        }
        return completed == 1;
    }

    private boolean prepareJobForDispatch(long ingestJobId, int attempt) {
        if (attempt <= 1) {
            Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ai_project_ingest_job where ingest_job_id = ? and attempt = 1 and status = ?",
                Integer.class, ingestJobId, JOB_UPLOADED);
            return count != null && count == 1;
        }
        int updated = jdbcTemplate.update(
            "update ai_project_ingest_job set status = ?, stage = ?, attempt = ?, progress = 0, next_retry_at = null, error_code = null, error_summary = null, lease_owner = null, lease_expires_at = null, heartbeat_at = null, queue_published_attempt = 0, updated_at = current_timestamp where ingest_job_id = ? and ((status = ? and attempt < ?) or (status = ? and attempt = ?))",
            JOB_UPLOADED, JOB_UPLOADED, attempt, ingestJobId,
            JOB_RETRYABLE_FAILED, attempt, JOB_UPLOADED, attempt);
        if (updated != 1) {
            return false;
        }
        jdbcTemplate.update(
            "update ai_project_ingest_generation set status = ?, error_code = null, error_summary = null, updated_at = current_timestamp where generation_id = (select generation_id from ai_project_ingest_job where ingest_job_id = ?) and status <> ?",
            GEN_PREPARED, ingestJobId, GEN_ACTIVE);
        return true;
    }

    private long retryBackoffSeconds(int nextAttempt) {
        return queueService == null ? 30L : queueService.retryBackoffSeconds(nextAttempt);
    }

    private void ensureChapterHead(Long userId, Long projectId, Long workId, int chapterNo) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_chapter_head where user_id = ? and project_id = ? and work_id = ? and chapter_no = ?",
            Integer.class, userId, projectId, workId, chapterNo);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
            "insert into ai_project_chapter_head(user_id, project_id, work_id, chapter_no, optimistic_version) values(?, ?, ?, ?, 0)",
            userId, projectId, workId, chapterNo);
    }

    private boolean isTombstoned(Long userId, Long projectId, Long workId, int chapterNo) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_chapter_head where user_id = ? and project_id = ? and work_id = ? and chapter_no = ? and tombstoned_at is not null",
            Integer.class, userId, projectId, workId, chapterNo);
        return count != null && count > 0;
    }

    private int countActiveJobs(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_ingest_job where user_id = ? and status in (?, ?, ?, ?, ?, ?)",
            Integer.class, userId, JOB_UPLOADED, JOB_PARSING, JOB_EXTRACTING, JOB_INDEXING, JOB_VERIFYING, JOB_RETRYABLE_FAILED);
        return count == null ? 0 : count;
    }

    private ProjectIngestJobVO findByIdempotency(Long userId, String idempotencyKey) {
        return jdbcTemplate.query(
            "select * from ai_project_ingest_job where user_id = ? and idempotency_key = ?",
            jobMapper(), userId, idempotencyKey).stream().map(this::withLabel).findFirst().orElse(null);
    }

    private int nextChapterVersion(Long workId, int chapterNo) {
        Integer max = jdbcTemplate.queryForObject(
            "select max(version) from ai_project_chapter where work_id = ? and chapter_no = ?",
            Integer.class, workId, chapterNo);
        return max == null ? 1 : max + 1;
    }

    private ProjectIngestJobVO withLabel(ProjectIngestJobVO job) {
        if (job != null) {
            job.setStatusLabel(statusLabel(job.getStatus()));
        }
        return job;
    }

    private String statusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case JOB_UPLOADED -> "等待处理";
            case JOB_PARSING -> "解析章节";
            case JOB_EXTRACTING -> "提取设定";
            case JOB_INDEXING -> "建立索引";
            case JOB_VERIFYING -> "校验结果";
            case JOB_READY -> "可使用";
            case JOB_RETRYABLE_FAILED -> "可重试";
            case JOB_TERMINAL_FAILED -> "失败";
            default -> status;
        };
    }

    private RowMapper<ProjectIngestJobVO> jobMapper() {
        return (rs, rowNum) -> {
            ProjectIngestJobVO vo = new ProjectIngestJobVO();
            vo.setIngestJobId(rs.getLong("ingest_job_id"));
            vo.setUserId(rs.getLong("user_id"));
            vo.setProjectId(rs.getLong("project_id"));
            vo.setWorkId(rs.getLong("work_id"));
            if (rs.getObject("chapter_id") != null) {
                vo.setChapterId(rs.getLong("chapter_id"));
            }
            if (rs.getObject("generation_id") != null) {
                vo.setGenerationId(rs.getLong("generation_id"));
            }
            if (rs.getObject("chapter_no") != null) {
                vo.setChapterNo(rs.getInt("chapter_no"));
            }
            vo.setIdempotencyKey(rs.getString("idempotency_key"));
            vo.setContentHash(rs.getString("content_hash"));
            vo.setParserVersion(rs.getString("parser_version"));
            vo.setStatus(rs.getString("status"));
            vo.setStage(rs.getString("stage"));
            vo.setProgress(rs.getInt("progress"));
            if (rs.getObject("attempt") != null) {
                vo.setAttempt(rs.getInt("attempt"));
            }
            if (rs.getObject("max_attempts") != null) {
                vo.setMaxAttempts(rs.getInt("max_attempts"));
            }
            if (rs.getObject("fencing_token") != null) {
                vo.setFencingToken(rs.getLong("fencing_token"));
            }
            vo.setErrorCode(rs.getString("error_code"));
            vo.setErrorSummary(rs.getString("error_summary"));
            try { vo.setTitle(rs.getString("title")); } catch (Exception ignored) {}
            try { vo.setSourceType(rs.getString("source_type")); } catch (Exception ignored) {}
            Timestamp created = rs.getTimestamp("created_at");
            if (created != null) {
                vo.setCreatedAt(created.toLocalDateTime());
            }
            Timestamp updated = rs.getTimestamp("updated_at");
            if (updated != null) {
                vo.setUpdatedAt(updated.toLocalDateTime());
            }
            return vo;
        };
    }

    private RowMapper<ProjectExtractionCandidateVO> candidateMapper() {
        return (rs, rowNum) -> {
            ProjectExtractionCandidateVO vo = new ProjectExtractionCandidateVO();
            vo.setCandidateId(rs.getLong("candidate_id"));
            vo.setUserId(rs.getLong("user_id"));
            vo.setProjectId(rs.getLong("project_id"));
            vo.setWorkId(rs.getLong("work_id"));
            if (rs.getObject("chapter_id") != null) {
                vo.setChapterId(rs.getLong("chapter_id"));
            }
            vo.setGenerationId(rs.getLong("generation_id"));
            vo.setEntityType(rs.getString("entity_type"));
            vo.setPayloadJson(rs.getString("payload"));
            vo.setEvidenceRefsJson(rs.getString("evidence_refs"));
            if (rs.getObject("confidence") != null) {
                vo.setConfidence(rs.getDouble("confidence"));
            }
            vo.setStatus(rs.getString("status"));
            vo.setReviewNote(rs.getString("review_note"));
            Timestamp created = rs.getTimestamp("created_at");
            if (created != null) {
                vo.setCreatedAt(created.toLocalDateTime());
            }
            Timestamp updated = rs.getTimestamp("updated_at");
            if (updated != null) {
                vo.setUpdatedAt(updated.toLocalDateTime());
            }
            return vo;
        };
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "login required");
        }
        return user;
    }

    private String requireText(String value, String message, int maxChars) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        if (value.length() > maxChars) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message + " too long");
        }
        return value.trim();
    }

    private String trimToNull(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record ClaimResult(ProjectIngestJobVO job, long fencingToken) {
    }

    public enum FailureDisposition {
        RETRY_SCHEDULED,
        TERMINAL,
        STALE
    }
}

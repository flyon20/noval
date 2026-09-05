package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.dto.ProjectDocumentQuestionAnswerRequest;
import com.novelanalyzer.modules.knowledge.dto.ProjectIngestSubmitRequest;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.DocumentKind;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentBatchVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentQuestionVO;
import com.novelanalyzer.modules.knowledge.vo.ProjectIngestJobVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeProjectDocumentBatchService {

    public static final String STORED = "STORED";
    public static final String PARSING = "PARSING";
    public static final String WAITING_CONFIRMATION = "WAITING_CONFIRMATION";
    public static final String PARSED_PENDING_INDEX = "PARSED_PENDING_INDEX";
    public static final String READY = "READY";
    public static final String RETRYABLE_FAILED = "RETRYABLE_FAILED";
    public static final String TERMINAL_FAILED = "TERMINAL_FAILED";
    public static final String CANCELLED = "CANCELLED";

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".md", ".markdown", ".zip");
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectWorkService workService;
    private final KnowledgeProjectIngestService ingestService;
    private final KnowledgeProjectDocumentBatchQueueService queueService;
    private final ProjectDocumentParser parser;
    private final KnowledgeProjectDocumentIndexService documentIndexService;
    private final KnowledgeProperties properties;

    public KnowledgeProjectDocumentBatchService(JdbcTemplate jdbcTemplate,
                                                KnowledgeProjectWorkService workService,
                                                KnowledgeProjectIngestService ingestService,
                                                KnowledgeProjectDocumentBatchQueueService queueService,
                                                ProjectDocumentParser parser,
                                                KnowledgeProjectDocumentIndexService documentIndexService,
                                                KnowledgeProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.workService = workService;
        this.ingestService = ingestService;
        this.queueService = queueService;
        this.parser = parser;
        this.documentIndexService = documentIndexService;
        this.properties = properties == null ? new KnowledgeProperties() : properties;
    }

    @Transactional
    public ProjectDocumentBatchVO create(Long projectId,
                                         Long workId,
                                         List<MultipartFile> files,
                                         List<String> relativePaths,
                                         String declaredKind,
                                         String idempotencyKey) {
        AuthUser user = requireUser();
        workService.findOwnedWorkPublic(projectId, workId, user.getUserId());
        KnowledgeProperties.DocumentBatch cfg = properties.getDocumentBatch();
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "at least one document file is required");
        }
        if (files.size() > Math.max(1, cfg.getMaxFiles())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "document batch file limit exceeded");
        }
        DocumentKind kind = normalizeKind(declaredKind, true);
        List<UploadFile> uploads = new ArrayList<>(files.size());
        Set<String> uniquePaths = new HashSet<>();
        long totalBytes = 0L;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file == null || file.isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "document file is empty");
            }
            String originalName = trim(file.getOriginalFilename(), 255);
            if (originalName == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "document filename is required");
            }
            String suppliedPath = relativePaths != null && index < relativePaths.size()
                ? relativePaths.get(index) : originalName;
            String relativePath = parser.normalizeRelativePath(suppliedPath, originalName);
            if (!uniquePaths.add(relativePath.toLowerCase(Locale.ROOT))) {
                throw new BusinessException(ResultCode.CONFLICT, "duplicate document relative path");
            }
            if (!parser.shouldIgnore(relativePath) && !hasSupportedExtension(relativePath)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "only TXT, Markdown or ZIP files are supported");
            }
            long size = file.getSize();
            if (size <= 0 || size > properties.getProjectIngest().getMaxFileBytes()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "document file exceeds size limit");
            }
            totalBytes += size;
            if (totalBytes > cfg.getMaxBatchBytes()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "document batch size limit exceeded");
            }
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException ex) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "document file could not be stored");
            }
            if (content.length != size) {
                size = content.length;
            }
            uploads.add(new UploadFile(
                relativePath,
                originalName,
                trim(file.getContentType(), 120),
                content,
                sha256(content),
                kind == DocumentKind.AUTO ? null : kind.name()
            ));
        }
        String manifestHash = manifestHash(uploads);
        String effectiveIdempotencyKey = trim(idempotencyKey, 200);
        if (effectiveIdempotencyKey == null) {
            effectiveIdempotencyKey = "auto:document-batch:" + workId + ":" + manifestHash;
        }
        ProjectDocumentBatchVO existing = findByIdempotency(user.getUserId(), effectiveIdempotencyKey);
        if (existing != null) {
            String existingManifest = jdbcTemplate.queryForObject(
                "select manifest_hash from ai_project_document_batch where batch_id = ?",
                String.class,
                existing.getBatchId()
            );
            if (!manifestHash.equals(existingManifest) || !workId.equals(existing.getWorkId())) {
                throw new BusinessException(ResultCode.CONFLICT,
                    "document batch idempotency key reused with different files");
            }
            return existing;
        }

        String finalIdempotencyKey = effectiveIdempotencyKey;
        long finalTotalBytes = uploads.stream().mapToLong(item -> item.content().length).sum();
        KeyHolder batchKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_document_batch(user_id, project_id, work_id, idempotency_key, "
                    + "manifest_hash, parser_version, status, stage, progress, total_files, stored_files, "
                    + "total_bytes, max_attempts) values(?, ?, ?, ?, ?, ?, 'STORED', 'stored', 5, ?, ?, ?, ?)",
                new String[]{"batch_id"}
            );
            statement.setLong(1, user.getUserId());
            statement.setLong(2, projectId);
            statement.setLong(3, workId);
            statement.setString(4, finalIdempotencyKey);
            statement.setString(5, manifestHash);
            statement.setString(6, properties.getProjectIngest().getParserVersion());
            statement.setInt(7, uploads.size());
            statement.setInt(8, uploads.size());
            statement.setLong(9, finalTotalBytes);
            statement.setInt(10, Math.max(1, cfg.getMaxAttempts()));
            return statement;
        }, batchKey);
        Number generated = batchKey.getKey();
        if (generated == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "document batch id missing");
        }
        long batchId = generated.longValue();
        for (UploadFile upload : uploads) {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    "insert into ai_project_document_file(batch_id, user_id, project_id, work_id, relative_path, "
                        + "original_name, media_type, size_bytes, content_hash, declared_kind, status, content_blob) "
                        + "values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                );
                statement.setLong(1, batchId);
                statement.setLong(2, user.getUserId());
                statement.setLong(3, projectId);
                statement.setLong(4, workId);
                statement.setString(5, upload.relativePath());
                statement.setString(6, upload.originalName());
                statement.setString(7, upload.mediaType());
                statement.setLong(8, upload.content().length);
                statement.setString(9, upload.contentHash());
                statement.setString(10, upload.declaredKind());
                statement.setString(11, parser.shouldIgnore(upload.relativePath()) ? "SKIPPED" : STORED);
                statement.setBytes(12, upload.content());
                return statement;
            });
        }
        insertOutbox(batchId, 1);
        dispatchAfterCommit(batchId, 1);
        return findOwned(user.getUserId(), projectId, batchId, false);
    }

    public ProjectDocumentBatchVO get(Long projectId, Long batchId) {
        AuthUser user = requireUser();
        return findOwned(user.getUserId(), projectId, batchId, true);
    }

    public List<ProjectDocumentBatchVO> list(Long projectId, Long workId, int limit) {
        AuthUser user = requireUser();
        workService.findOwnedWorkPublic(projectId, workId, user.getUserId());
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
            "select * from ai_project_document_batch where user_id = ? and project_id = ? and work_id = ? "
                + "order by batch_id desc limit ?",
            batchMapper(), user.getUserId(), projectId, workId, safeLimit
        );
    }

    public List<ProjectDocumentQuestionVO> listQuestions(Long projectId, Long batchId) {
        AuthUser user = requireUser();
        ProjectDocumentBatchVO batch = findOwned(user.getUserId(), projectId, batchId, false);
        if (!WAITING_CONFIRMATION.equals(batch.getStatus())) {
            return List.of();
        }
        return jdbcTemplate.query(
            "select q.*, f.relative_path from ai_project_document_question q "
                + "left join ai_project_document_file f on f.file_id = q.file_id "
                + "where q.user_id = ? and q.project_id = ? and q.batch_id = ? order by q.question_id",
            questionMapper(), user.getUserId(), projectId, batchId
        );
    }

    @Transactional
    public ProjectDocumentQuestionVO answerQuestion(Long projectId,
                                                    Long batchId,
                                                    Long questionId,
                                                    ProjectDocumentQuestionAnswerRequest request) {
        AuthUser user = requireUser();
        ProjectDocumentBatchVO batch = findOwned(user.getUserId(), projectId, batchId, false);
        if (!WAITING_CONFIRMATION.equals(batch.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                "document batch is not waiting for confirmation");
        }
        String answer = request == null ? null : request.getAnswer();
        DocumentKind kind = normalizeKind(answer, false);
        List<ProjectDocumentQuestionVO> matches = jdbcTemplate.query(
            "select q.*, f.relative_path from ai_project_document_question q "
                + "left join ai_project_document_file f on f.file_id = q.file_id "
                + "where q.question_id = ? and q.batch_id = ? and q.user_id = ? and q.project_id = ?",
            questionMapper(), questionId, batchId, user.getUserId(), projectId
        );
        if (matches.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "document question not found");
        }
        ProjectDocumentQuestionVO question = matches.get(0);
        if (!"PENDING".equals(question.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "document question is already resolved");
        }
        int updated = jdbcTemplate.update(
            "update ai_project_document_question set answer_json = ?, status = 'RESOLVED', resolved_by = ?, "
                + "resolved_at = current_timestamp, updated_at = current_timestamp "
                + "where question_id = ? and status = 'PENDING'",
            jsonString(kind.name()), user.getUserId(), questionId
        );
        if (updated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "document question update lost");
        }
        if (question.getFileId() != null) {
            jdbcTemplate.update(
                "update ai_project_document_file set declared_kind = ?, updated_at = current_timestamp where file_id = ?",
                kind.name(), question.getFileId()
            );
        }
        Integer pending = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_question where batch_id = ? and status = 'PENDING'",
            Integer.class,
            batchId
        );
        jdbcTemplate.update(
            "update ai_project_document_batch set pending_questions = ?, updated_at = current_timestamp where batch_id = ?",
            pending == null ? 0 : pending,
            batchId
        );
        if (pending == null || pending == 0) {
            resetParsedDraft(batch);
        }
        return jdbcTemplate.query(
            "select q.*, f.relative_path from ai_project_document_question q "
                + "left join ai_project_document_file f on f.file_id = q.file_id where q.question_id = ?",
            questionMapper(), questionId
        ).get(0);
    }

    @Transactional
    public ProjectDocumentBatchVO retry(Long projectId, Long batchId) {
        AuthUser user = requireUser();
        ProjectDocumentBatchVO batch = findOwned(user.getUserId(), projectId, batchId, true);
        if (!RETRYABLE_FAILED.equals(batch.getStatus()) && !TERMINAL_FAILED.equals(batch.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "only failed document batches can be retried");
        }
        List<Long> childJobs = jdbcTemplate.queryForList(
            "select distinct j.ingest_job_id from ai_project_document_section s "
                + "join ai_project_document d on d.document_id = s.document_id "
                + "join ai_project_ingest_job j on j.ingest_job_id = s.ingest_job_id "
                + "where d.batch_id = ? and s.user_id = ? and s.project_id = ? and s.status = 'INDEXING' "
                + "and j.status in ('RETRYABLE_FAILED','TERMINAL_FAILED')",
            Long.class,
            batchId, user.getUserId(), projectId
        );
        if (!childJobs.isEmpty()) {
            for (Long childJob : childJobs) {
                ingestService.retry(projectId, childJob);
            }
            jdbcTemplate.update(
                "update ai_project_document_batch set status = ?, stage = 'indexing', progress = 80, "
                    + "failed_files = 0, error_code = null, error_summary = null, updated_at = current_timestamp "
                    + "where batch_id = ?",
                PARSED_PENDING_INDEX, batchId
            );
            return findOwned(user.getUserId(), projectId, batchId, false);
        }
        Integer failedDocumentSections = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section s join ai_project_document d on d.document_id = s.document_id "
                + "where d.batch_id = ? and s.section_kind <> 'NOVEL_TEXT' and s.status = 'INDEX_FAILED'",
            Integer.class,
            batchId
        );
        if (failedDocumentSections != null && failedDocumentSections > 0) {
            jdbcTemplate.update(
                "update ai_project_document_section set status = 'PARSED_PENDING_INDEX', updated_at = current_timestamp "
                    + "where document_id in (select document_id from ai_project_document where batch_id = ?) "
                    + "and section_kind <> 'NOVEL_TEXT' and status = 'INDEX_FAILED'",
                batchId
            );
            jdbcTemplate.update(
                "update ai_project_document_batch set status = ?, stage = 'indexing', progress = 80, "
                    + "failed_files = 0, error_code = null, error_summary = null, updated_at = current_timestamp "
                    + "where batch_id = ?",
                PARSED_PENDING_INDEX, batchId
            );
            return findOwned(user.getUserId(), projectId, batchId, false);
        }
        int nextAttempt = Math.max(1, batch.getAttempt() == null ? 1 : batch.getAttempt() + 1);
        int updated = jdbcTemplate.update(
            "update ai_project_document_batch set status = ?, stage = 'stored', progress = 5, attempt = ?, "
                + "lease_owner = null, lease_expires_at = null, heartbeat_at = null, "
                + "fencing_token = fencing_token + 1, failed_files = 0, error_code = null, error_summary = null, "
                + "updated_at = current_timestamp where batch_id = ? and user_id = ? "
                + "and status in (?, ?)",
            STORED, nextAttempt, batchId, user.getUserId(), RETRYABLE_FAILED, TERMINAL_FAILED
        );
        if (updated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "document batch retry rejected");
        }
        insertOutbox(batchId, nextAttempt);
        dispatchAfterCommit(batchId, nextAttempt);
        return findOwned(user.getUserId(), projectId, batchId, false);
    }

    @Transactional
    public ProjectDocumentBatchVO cancel(Long projectId, Long batchId) {
        AuthUser user = requireUser();
        ProjectDocumentBatchVO batch = findOwned(user.getUserId(), projectId, batchId, true);
        if (Set.of(READY, CANCELLED, TERMINAL_FAILED).contains(batch.getStatus())) {
            return batch;
        }
        if (PARSED_PENDING_INDEX.equals(batch.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "document indexing has already started");
        }
        jdbcTemplate.update(
            "update ai_project_document_batch set cancel_requested = 1, status = ?, stage = 'cancelled', "
                + "pending_questions = 0, completed_at = current_timestamp, updated_at = current_timestamp "
                + "where batch_id = ? and user_id = ?",
            CANCELLED, batchId, user.getUserId()
        );
        jdbcTemplate.update(
            "update ai_project_document_question set status = 'DISCARDED', resolved_by = ?, "
                + "resolved_at = current_timestamp, updated_at = current_timestamp "
                + "where batch_id = ? and status = 'PENDING'",
            user.getUserId(), batchId
        );
        jdbcTemplate.update(
            "update ai_project_document_batch_outbox set status = 'DEAD', last_error = 'batch cancelled', "
                + "updated_at = current_timestamp where batch_id = ? and status in ('PENDING','DISPATCHING')",
            batchId
        );
        return findOwned(user.getUserId(), projectId, batchId, false);
    }

    @Transactional
    public void discard(Long projectId, Long batchId) {
        AuthUser user = requireUser();
        List<ProjectDocumentBatchVO> rows = jdbcTemplate.query(
            "select * from ai_project_document_batch where batch_id = ? and user_id = ? and project_id = ?",
            batchMapper(), batchId, user.getUserId(), projectId
        );
        if (rows.isEmpty()) {
            return;
        }
        ProjectDocumentBatchVO batch = rows.get(0);
        if (!CANCELLED.equals(batch.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                "only cancelled document batches can be discarded");
        }
        if (hasIndexedProjection(batchId)) {
            throw new BusinessException(ResultCode.CONFLICT,
                "document batch already has indexed knowledge and cannot be discarded");
        }

        jdbcTemplate.update("delete from ai_project_document_question where batch_id = ?", batchId);
        jdbcTemplate.update(
            "delete from ai_project_document_section where document_id in "
                + "(select document_id from ai_project_document where batch_id = ?)",
            batchId
        );
        jdbcTemplate.update("delete from ai_project_document_generation where batch_id = ?", batchId);
        jdbcTemplate.update("delete from ai_project_document where batch_id = ?", batchId);
        jdbcTemplate.update("delete from ai_project_document_file where batch_id = ?", batchId);
        jdbcTemplate.update("delete from ai_project_document_batch_outbox where batch_id = ?", batchId);
        int deleted = jdbcTemplate.update(
            "delete from ai_project_document_batch where batch_id = ? and user_id = ? and project_id = ? and status = ?",
            batchId, user.getUserId(), projectId, CANCELLED
        );
        if (deleted != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "document batch discard lost");
        }
    }

    private boolean hasIndexedProjection(Long batchId) {
        Integer submittedSections = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section where document_id in "
                + "(select document_id from ai_project_document where batch_id = ?) "
                + "and (ingest_job_id is not null or status in ('INDEXING','ACTIVE'))",
            Integer.class,
            batchId
        );
        Integer activeDocuments = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document where batch_id = ? and active_generation_id is not null",
            Integer.class,
            batchId
        );
        Integer vectorRows = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_vector_chunk where document_generation_id in "
                + "(select document_generation_id from ai_project_document_generation where batch_id = ?)",
            Integer.class,
            batchId
        );
        Integer searchRows = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_search_document where document_generation_id in "
                + "(select document_generation_id from ai_project_document_generation where batch_id = ?)",
            Integer.class,
            batchId
        );
        Integer evidenceRows = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_entity_evidence where document_generation_id in "
                + "(select document_generation_id from ai_project_document_generation where batch_id = ?)",
            Integer.class,
            batchId
        );
        return positive(submittedSections) || positive(activeDocuments) || positive(vectorRows)
            || positive(searchRows) || positive(evidenceRows);
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    public void dispatchPendingOutbox() {
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
            "select outbox_id, batch_id, attempt from ai_project_document_batch_outbox "
                + "where (status = 'PENDING' and available_at <= current_timestamp) "
                + "or (status = 'DISPATCHING' and updated_at < ?) order by outbox_id limit 20",
            Timestamp.from(Instant.now().minusSeconds(30))
        );
        for (Map<String, Object> row : pending) {
            dispatchOutbox(
                ((Number) row.get("outbox_id")).longValue(),
                ((Number) row.get("batch_id")).longValue(),
                ((Number) row.get("attempt")).intValue()
            );
        }
        List<Long> indexing = jdbcTemplate.queryForList(
            "select batch_id from ai_project_document_batch where status = ? order by batch_id limit 20",
            Long.class,
            PARSED_PENDING_INDEX
        );
        for (Long batchId : indexing) {
            refreshCompletion(findById(batchId));
        }
    }

    public ClaimedBatch claim(Long batchId, int expectedAttempt, String owner, Duration lease) {
        int updated = jdbcTemplate.update(
            "update ai_project_document_batch set status = ?, stage = 'parsing', progress = 10, lease_owner = ?, "
                + "lease_expires_at = ?, heartbeat_at = current_timestamp, fencing_token = fencing_token + 1, "
                + "updated_at = current_timestamp where batch_id = ? and attempt = ? and cancel_requested = 0 "
                + "and status = ?",
            PARSING, owner, Timestamp.from(Instant.now().plus(lease)), batchId, expectedAttempt, STORED
        );
        if (updated != 1) {
            return null;
        }
        ProjectDocumentBatchVO batch = findById(batchId);
        Long token = jdbcTemplate.queryForObject(
            "select fencing_token from ai_project_document_batch where batch_id = ?",
            Long.class,
            batchId
        );
        return new ClaimedBatch(batch, token == null ? 0L : token);
    }

    public List<StoredFile> storedFiles(Long batchId, long fencingToken) {
        return jdbcTemplate.query(
            "select file_id, relative_path, original_name, content_blob, declared_kind, status "
                + "from ai_project_document_file where batch_id = ? order by file_id",
            (rs, rowNum) -> new StoredFile(
                rs.getLong("file_id"),
                rs.getString("relative_path"),
                rs.getString("original_name"),
                rs.getBytes("content_blob"),
                rs.getString("declared_kind"),
                rs.getString("status")
            ),
            batchId
        );
    }

    public void clearUnindexedDrafts(Long batchId, long fencingToken) {
        Integer submitted = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section where document_id in "
                + "(select document_id from ai_project_document where batch_id = ?) and ingest_job_id is not null",
            Integer.class,
            batchId
        );
        if (submitted != null && submitted > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "indexed document draft cannot be replaced");
        }
        Integer owned = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_batch where batch_id = ? and status = ? and fencing_token = ?",
            Integer.class,
            batchId, PARSING, fencingToken
        );
        if (owned == null || owned != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "document batch lease was lost");
        }
        jdbcTemplate.update(
            "delete from ai_project_document_question where batch_id = ? and status = 'PENDING'",
            batchId
        );
        jdbcTemplate.update(
            "delete from ai_project_document_section where document_id in "
                + "(select document_id from ai_project_document where batch_id = ?)",
            batchId
        );
        jdbcTemplate.update("delete from ai_project_document_generation where batch_id = ?", batchId);
        jdbcTemplate.update("delete from ai_project_document where batch_id = ?", batchId);
        jdbcTemplate.update(
            "update ai_project_document_file set document_id = null, status = case when status = 'SKIPPED' "
                + "then 'SKIPPED' else 'STORED' end, error_code = null, error_summary = null, "
                + "updated_at = current_timestamp where batch_id = ?",
            batchId
        );
    }

    public boolean updateProgress(Long batchId, long token, int progress, int parsed, int skipped) {
        return jdbcTemplate.update(
            "update ai_project_document_batch set progress = ?, parsed_files = ?, skipped_files = ?, "
                + "heartbeat_at = current_timestamp, updated_at = current_timestamp "
                + "where batch_id = ? and status = ? and fencing_token = ? and cancel_requested = 0",
            Math.max(10, Math.min(progress, 75)), parsed, skipped, batchId, PARSING, token
        ) == 1;
    }

    public void markWaiting(Long batchId, long token, int parsed, int skipped, int questions) {
        int updated = jdbcTemplate.update(
            "update ai_project_document_batch set status = ?, stage = 'waiting_confirmation', progress = 70, "
                + "parsed_files = ?, skipped_files = ?, pending_questions = ?, lease_owner = null, "
                + "lease_expires_at = null, heartbeat_at = null, updated_at = current_timestamp "
                + "where batch_id = ? and status = ? and fencing_token = ?",
            WAITING_CONFIRMATION, parsed, skipped, questions, batchId, PARSING, token
        );
        if (updated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "document batch lease was lost");
        }
    }

    public void markPendingIndex(Long batchId, long token, int parsed, int skipped) {
        int updated = jdbcTemplate.update(
            "update ai_project_document_batch set status = ?, stage = 'indexing', progress = 80, "
                + "parsed_files = ?, skipped_files = ?, pending_questions = 0, lease_owner = null, "
                + "lease_expires_at = null, heartbeat_at = null, updated_at = current_timestamp "
                + "where batch_id = ? and status = ? and fencing_token = ?",
            PARSED_PENDING_INDEX, parsed, skipped, batchId, PARSING, token
        );
        if (updated != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "document batch lease was lost");
        }
    }

    public ProjectDocumentBatchVO advanceIndex(Long batchId) {
        return refreshCompletion(findById(batchId));
    }

    public void markFailed(Long batchId, long token, RuntimeException error) {
        String summary = trim(error == null ? null : error.getMessage(), 1000);
        jdbcTemplate.update(
            "update ai_project_document_batch set status = ?, stage = 'failed', failed_files = failed_files + 1, "
                + "error_code = 'PARSE_FAILED', error_summary = ?, lease_owner = null, lease_expires_at = null, "
                + "heartbeat_at = null, updated_at = current_timestamp where batch_id = ? and status = ? "
                + "and fencing_token = ?",
            RETRYABLE_FAILED, summary, batchId, PARSING, token
        );
    }

    private ProjectDocumentBatchVO refreshCompletion(ProjectDocumentBatchVO batch) {
        if (batch == null || !PARSED_PENDING_INDEX.equals(batch.getStatus())) {
            return batch;
        }
        try {
            documentIndexService.indexPendingSections(
                batch,
                Math.max(1, properties.getDocumentBatch().getDocumentIndexBatchSize())
            );
        } catch (RuntimeException ex) {
            jdbcTemplate.update(
                "update ai_project_document_batch set status = ?, stage = 'index_failed', failed_files = failed_files + 1, "
                    + "error_code = 'DOCUMENT_INDEX_FAILED', error_summary = ?, updated_at = current_timestamp "
                    + "where batch_id = ? and status = ?",
                RETRYABLE_FAILED, trim(ex.getMessage(), 1000), batch.getBatchId(), PARSED_PENDING_INDEX
            );
            return findById(batch.getBatchId());
        }
        Map<String, Object> counts = jdbcTemplate.queryForMap(
            "select count(*) total_count, "
                + "coalesce(sum(case when s.status = 'ACTIVE' then 1 else 0 end), 0) active_count, "
                + "coalesce(sum(case when s.section_kind = 'NOVEL_TEXT' and s.ingest_job_id is not null then 1 else 0 end), 0) submitted_count, "
                + "coalesce(sum(case when s.section_kind = 'NOVEL_TEXT' and j.status = 'READY' then 1 else 0 end), 0) ready_count, "
                + "coalesce(sum(case when s.status = 'INDEX_FAILED' or j.status in ('RETRYABLE_FAILED','TERMINAL_FAILED') then 1 else 0 end), 0) failed_count "
                + "from ai_project_document_section s "
                + "join ai_project_document d on d.document_id = s.document_id "
                + "left join ai_project_ingest_job j on j.ingest_job_id = s.ingest_job_id "
                + "where d.batch_id = ? and s.user_id = ? and s.project_id = ? and s.work_id = ?",
            batch.getBatchId(), batch.getUserId(), batch.getProjectId(), batch.getWorkId()
        );
        int total = ((Number) counts.get("total_count")).intValue();
        int active = ((Number) counts.get("active_count")).intValue();
        int submitted = ((Number) counts.get("submitted_count")).intValue();
        int ready = ((Number) counts.get("ready_count")).intValue();
        int failed = ((Number) counts.get("failed_count")).intValue();
        if (failed > 0) {
            jdbcTemplate.update(
                "update ai_project_document_batch set status = ?, stage = 'index_failed', failed_files = ?, "
                    + "error_code = 'CHILD_INGEST_FAILED', error_summary = 'one or more document sections failed to index', "
                    + "updated_at = current_timestamp where batch_id = ? and status = ?",
                RETRYABLE_FAILED, failed, batch.getBatchId(), PARSED_PENDING_INDEX
            );
            return findById(batch.getBatchId());
        }
        int dispatchWindow = Math.max(1, Math.min(32, properties.getDocumentBatch().getChapterDispatchWindow()));
        if (submitted - ready < dispatchWindow) {
            try {
                submitNextSections(batch, dispatchWindow - (submitted - ready));
            } catch (BusinessException ex) {
                if (ex.getResultCode() != ResultCode.TOO_MANY_REQUESTS
                    && ex.getResultCode() != ResultCode.SERVICE_UNAVAILABLE) {
                    throw ex;
                }
            }
            batch = findById(batch.getBatchId());
            counts = jdbcTemplate.queryForMap(
                "select count(*) total_count, "
                    + "coalesce(sum(case when s.status = 'ACTIVE' then 1 else 0 end), 0) active_count, "
                    + "coalesce(sum(case when s.section_kind = 'NOVEL_TEXT' and j.status = 'READY' then 1 else 0 end), 0) ready_count "
                    + "from ai_project_document_section s join ai_project_document d on d.document_id = s.document_id "
                    + "left join ai_project_ingest_job j on j.ingest_job_id = s.ingest_job_id "
                    + "where d.batch_id = ? and s.user_id = ? and s.project_id = ? and s.work_id = ?",
                batch.getBatchId(), batch.getUserId(), batch.getProjectId(), batch.getWorkId()
            );
            total = ((Number) counts.get("total_count")).intValue();
            active = ((Number) counts.get("active_count")).intValue();
            ready = ((Number) counts.get("ready_count")).intValue();
        }
        int novelCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section s join ai_project_document d on d.document_id = s.document_id "
                + "where d.batch_id = ? and s.section_kind = 'NOVEL_TEXT'",
            Integer.class,
            batch.getBatchId()
        );
        if (total > 0 && active + ready == total && ready == novelCount) {
            jdbcTemplate.update(
                "update ai_project_document_section set status = 'ACTIVE', updated_at = current_timestamp "
                    + "where document_id in (select document_id from ai_project_document where batch_id = ?)",
                batch.getBatchId()
            );
            jdbcTemplate.update(
                "update ai_project_document_generation set status = 'ACTIVE', indexed_section_count = section_count, "
                    + "activated_at = current_timestamp, updated_at = current_timestamp where batch_id = ?",
                batch.getBatchId()
            );
            jdbcTemplate.update(
                "update ai_project_document d set status = 'ACTIVE', active_generation_id = "
                    + "(select max(g.document_generation_id) from ai_project_document_generation g "
                    + "where g.document_id = d.document_id and g.status = 'ACTIVE'), updated_at = current_timestamp "
                    + "where d.batch_id = ?",
                batch.getBatchId()
            );
            jdbcTemplate.update(
                "update ai_project_document_batch set status = ?, stage = 'ready', progress = 100, "
                    + "indexed_files = parsed_files, completed_at = current_timestamp, updated_at = current_timestamp "
                    + "where batch_id = ? and status = ?",
                READY, batch.getBatchId(), PARSED_PENDING_INDEX
            );
            return findById(batch.getBatchId());
        }
        return batch;
    }

    private void submitNextSections(ProjectDocumentBatchVO batch, int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select s.section_id, s.section_ordinal, s.title, s.section_kind, s.content, s.content_hash, "
                + "d.document_id from ai_project_document_section s "
                + "join ai_project_document d on d.document_id = s.document_id "
                + "where d.batch_id = ? and s.section_kind = 'NOVEL_TEXT' and s.ingest_job_id is null "
                + "and s.status = 'PARSED_PENDING_INDEX' order by d.document_id, s.section_ordinal limit ?",
            batch.getBatchId(), Math.max(1, Math.min(32, limit))
        );
        if (rows.isEmpty()) {
            return;
        }
        int nextChapterNo = jdbcTemplate.queryForObject(
            "select greatest("
                + "(select coalesce(max(chapter_no), 0) from ai_project_chapter where user_id = ? and project_id = ? and work_id = ? and status <> 'ARCHIVED'), "
                + "(select coalesce(max(s.canonical_chapter_no), 0) from ai_project_document_section s "
                + "where s.user_id = ? and s.project_id = ? and s.work_id = ?)) + 1",
            Integer.class,
            batch.getUserId(), batch.getProjectId(), batch.getWorkId(),
            batch.getUserId(), batch.getProjectId(), batch.getWorkId()
        );
        AuthUser previous = AuthUserHolder.get();
        try {
            AuthUserHolder.set(AuthUser.of(batch.getUserId(), "document-batch-worker", Set.of("USER")));
            for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> row = rows.get(index);
                int chapterNo = nextChapterNo + index;
                Long sectionId = ((Number) row.get("section_id")).longValue();
                ProjectIngestSubmitRequest request = new ProjectIngestSubmitRequest();
                request.setChapterNo(chapterNo);
                request.setTitle(trim((String) row.get("title"), 200));
                request.setContent((String) row.get("content"));
                request.setSourceType("document_novel_text");
                request.setParserVersion(properties.getProjectIngest().getParserVersion());
                request.setIdempotencyKey(
                    "document-batch:" + batch.getBatchId() + ":" + row.get("document_id") + ":"
                        + row.get("section_ordinal") + ":" + row.get("content_hash")
                );
                ProjectIngestJobVO job = ingestService.submit(batch.getProjectId(), batch.getWorkId(), request);
                int updated = jdbcTemplate.update(
                    "update ai_project_document_section set ingest_job_id = ?, canonical_chapter_no = ?, "
                        + "status = 'INDEXING', updated_at = current_timestamp "
                        + "where section_id = ? and ingest_job_id is null and status = 'PARSED_PENDING_INDEX'",
                    job.getIngestJobId(), chapterNo, sectionId
                );
                if (updated != 1) {
                    throw new BusinessException(ResultCode.CONFLICT, "document section dispatch lost");
                }
            }
        } finally {
            if (previous == null) {
                AuthUserHolder.clear();
            } else {
                AuthUserHolder.set(previous);
            }
        }
    }

    private void resetParsedDraft(ProjectDocumentBatchVO batch) {
        Integer indexed = jdbcTemplate.queryForObject(
            "select count(*) from ai_project_document_section where document_id in "
                + "(select document_id from ai_project_document where batch_id = ?) and ingest_job_id is not null",
            Integer.class,
            batch.getBatchId()
        );
        if (indexed != null && indexed > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "confirmed document batch has already started indexing");
        }
        jdbcTemplate.update(
            "delete from ai_project_document_section where document_id in "
                + "(select document_id from ai_project_document where batch_id = ?)", batch.getBatchId());
        jdbcTemplate.update("delete from ai_project_document_generation where batch_id = ?", batch.getBatchId());
        jdbcTemplate.update("delete from ai_project_document where batch_id = ?", batch.getBatchId());
        jdbcTemplate.update(
            "update ai_project_document_file set document_id = null, status = case when status = 'SKIPPED' "
                + "then 'SKIPPED' else 'STORED' end, updated_at = current_timestamp where batch_id = ?",
            batch.getBatchId()
        );
        int nextAttempt = (batch.getAttempt() == null ? 1 : batch.getAttempt()) + 1;
        jdbcTemplate.update(
            "update ai_project_document_batch set status = ?, stage = 'stored', progress = 5, attempt = ?, "
                + "parsed_files = 0, indexed_files = 0, failed_files = 0, pending_questions = 0, "
                + "error_code = null, error_summary = null, updated_at = current_timestamp where batch_id = ?",
            STORED, nextAttempt, batch.getBatchId()
        );
        insertOutbox(batch.getBatchId(), nextAttempt);
        dispatchAfterCommit(batch.getBatchId(), nextAttempt);
    }

    private void insertOutbox(long batchId, int attempt) {
        String payload = "{\"batchId\":" + batchId + ",\"attempt\":" + attempt + "}";
        int updated = jdbcTemplate.update(
            "update ai_project_document_batch_outbox set payload = ?, status = 'PENDING', available_at = current_timestamp, "
                + "published_at = null, last_error = null, updated_at = current_timestamp "
                + "where batch_id = ? and event_type = 'PARSE' and attempt = ?",
            payload, batchId, attempt
        );
        if (updated == 0) {
            jdbcTemplate.update(
                "insert into ai_project_document_batch_outbox(batch_id, event_type, attempt, payload, status, available_at) "
                    + "values(?, 'PARSE', ?, ?, 'PENDING', current_timestamp)",
                batchId, attempt, payload
            );
        }
    }

    private void dispatchAfterCommit(long batchId, int attempt) {
        Runnable dispatch = () -> {
            List<Long> outboxIds = jdbcTemplate.queryForList(
                "select outbox_id from ai_project_document_batch_outbox where batch_id = ? and event_type = 'PARSE' "
                    + "and attempt = ? and status in ('PENDING','DISPATCHING') order by outbox_id limit 1",
                Long.class, batchId, attempt
            );
            if (!outboxIds.isEmpty()) {
                dispatchOutbox(outboxIds.get(0), batchId, attempt);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    private void dispatchOutbox(long outboxId, long batchId, int attempt) {
        int claimed = jdbcTemplate.update(
            "update ai_project_document_batch_outbox set status = 'DISPATCHING', attempt_count = attempt_count + 1, "
                + "updated_at = current_timestamp where outbox_id = ? and status in ('PENDING','DISPATCHING')",
            outboxId
        );
        if (claimed != 1) {
            return;
        }
        if (queueService.publish(batchId, attempt)) {
            jdbcTemplate.update(
                "update ai_project_document_batch_outbox set status = 'PUBLISHED', published_at = current_timestamp, "
                    + "last_error = null, updated_at = current_timestamp where outbox_id = ?",
                outboxId
            );
        } else {
            jdbcTemplate.update(
                "update ai_project_document_batch_outbox set status = 'PENDING', available_at = ?, "
                    + "last_error = 'rabbit publish not confirmed', updated_at = current_timestamp where outbox_id = ?",
                Timestamp.from(Instant.now().plusSeconds(5)), outboxId
            );
        }
    }

    private ProjectDocumentBatchVO findOwned(Long userId,
                                             Long projectId,
                                             Long batchId,
                                             boolean notFoundOnMismatch) {
        List<ProjectDocumentBatchVO> rows = jdbcTemplate.query(
            "select * from ai_project_document_batch where batch_id = ? and user_id = ? and project_id = ?",
            batchMapper(), batchId, userId, projectId
        );
        if (rows.isEmpty()) {
            throw new BusinessException(notFoundOnMismatch ? ResultCode.NOT_FOUND : ResultCode.NOT_FOUND,
                "document batch not found");
        }
        return rows.get(0);
    }

    private ProjectDocumentBatchVO findById(Long batchId) {
        List<ProjectDocumentBatchVO> rows = jdbcTemplate.query(
            "select * from ai_project_document_batch where batch_id = ?",
            batchMapper(), batchId
        );
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "document batch not found");
        }
        return rows.get(0);
    }

    private ProjectDocumentBatchVO findByIdempotency(Long userId, String key) {
        List<ProjectDocumentBatchVO> rows = jdbcTemplate.query(
            "select * from ai_project_document_batch where user_id = ? and idempotency_key = ?",
            batchMapper(), userId, key
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RowMapper<ProjectDocumentBatchVO> batchMapper() {
        return (rs, rowNum) -> {
            ProjectDocumentBatchVO value = new ProjectDocumentBatchVO();
            value.setBatchId(rs.getLong("batch_id"));
            value.setUserId(rs.getLong("user_id"));
            value.setProjectId(rs.getLong("project_id"));
            value.setWorkId(rs.getLong("work_id"));
            value.setStatus(rs.getString("status"));
            value.setStatusLabel(statusLabel(value.getStatus()));
            value.setStage(rs.getString("stage"));
            value.setProgress(rs.getInt("progress"));
            value.setTotalFiles(rs.getInt("total_files"));
            value.setStoredFiles(rs.getInt("stored_files"));
            value.setParsedFiles(rs.getInt("parsed_files"));
            value.setIndexedFiles(rs.getInt("indexed_files"));
            value.setSkippedFiles(rs.getInt("skipped_files"));
            value.setFailedFiles(rs.getInt("failed_files"));
            value.setPendingQuestions(rs.getInt("pending_questions"));
            value.setTotalBytes(rs.getLong("total_bytes"));
            value.setAttempt(rs.getInt("attempt"));
            value.setMaxAttempts(rs.getInt("max_attempts"));
            value.setErrorCode(rs.getString("error_code"));
            value.setErrorSummary(rs.getString("error_summary"));
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp updated = rs.getTimestamp("updated_at");
            value.setCreatedAt(created == null ? null : created.toLocalDateTime());
            value.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
            return value;
        };
    }

    private RowMapper<ProjectDocumentQuestionVO> questionMapper() {
        return (rs, rowNum) -> {
            ProjectDocumentQuestionVO value = new ProjectDocumentQuestionVO();
            value.setQuestionId(rs.getLong("question_id"));
            value.setBatchId(rs.getLong("batch_id"));
            value.setFileId(nullableLong(rs, "file_id"));
            value.setDocumentId(nullableLong(rs, "document_id"));
            value.setRelativePath(rs.getString("relative_path"));
            value.setQuestionType(rs.getString("question_type"));
            value.setPrompt(rs.getString("prompt"));
            value.setOptionsJson(rs.getString("options_json"));
            value.setAnswerJson(rs.getString("answer_json"));
            value.setStatus(rs.getString("status"));
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp resolved = rs.getTimestamp("resolved_at");
            value.setCreatedAt(created == null ? null : created.toLocalDateTime());
            value.setResolvedAt(resolved == null ? null : resolved.toLocalDateTime());
            return value;
        };
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private DocumentKind normalizeKind(String value, boolean allowAuto) {
        String normalized = trim(value, 40);
        if (normalized == null && allowAuto) {
            return DocumentKind.AUTO;
        }
        try {
            DocumentKind kind = DocumentKind.valueOf(normalized == null ? "" : normalized.toUpperCase(Locale.ROOT));
            if ((!allowAuto && kind == DocumentKind.AUTO) || kind == DocumentKind.MIXED) {
                throw new IllegalArgumentException();
            }
            return kind;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported project document kind");
        }
    }

    private boolean hasSupportedExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String manifestHash(List<UploadFile> files) {
        StringBuilder manifest = new StringBuilder();
        for (UploadFile file : files) {
            manifest.append(file.relativePath()).append('\0')
                .append(file.content().length).append('\0')
                .append(file.contentHash()).append('\n');
        }
        return sha256(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String jsonString(String value) {
        return "\"" + value + "\"";
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case STORED -> "等待解析";
            case PARSING -> "正在智能解析";
            case WAITING_CONFIRMATION -> "等待确认";
            case PARSED_PENDING_INDEX -> "正在建立知识索引";
            case READY -> "可用于 AI";
            case RETRYABLE_FAILED -> "可重试";
            case TERMINAL_FAILED -> "处理失败";
            case CANCELLED -> "已取消";
            default -> status;
        };
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "login required");
        }
        return user;
    }

    private record UploadFile(String relativePath,
                              String originalName,
                              String mediaType,
                              byte[] content,
                              String contentHash,
                              String declaredKind) {
    }

    public record StoredFile(Long fileId,
                             String relativePath,
                             String originalName,
                             byte[] content,
                             String declaredKind,
                             String status) {
    }

    public record ClaimedBatch(ProjectDocumentBatchVO batch, long fencingToken) {
    }
}

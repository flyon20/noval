package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchService.ClaimedBatch;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectDocumentBatchService.StoredFile;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.DocumentInput;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.DocumentKind;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.DocumentSection;
import com.novelanalyzer.modules.knowledge.service.ProjectDocumentParser.ParsedDocument;
import com.novelanalyzer.modules.knowledge.vo.ProjectDocumentBatchVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class KnowledgeProjectDocumentBatchExecutor
    implements KnowledgeProjectDocumentBatchRabbitConsumer.ExecutionPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectDocumentBatchExecutor.class);
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".txt", ".md", ".markdown");
    private static final String KIND_OPTIONS = "[\"NOVEL_TEXT\",\"OUTLINE\",\"CHAPTER_OUTLINE\","
        + "\"CHARACTER_PROFILE\",\"WORLD_SETTING\",\"TIMELINE\",\"FORESHADOWING_NOTE\","
        + "\"REFERENCE\",\"READER_FEEDBACK\"]";

    private final KnowledgeProjectDocumentBatchService batchService;
    private final ProjectDocumentParser parser;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeProjectDocumentBatchExecutor(KnowledgeProjectDocumentBatchService batchService,
                                                 ProjectDocumentParser parser,
                                                 JdbcTemplate jdbcTemplate,
                                                 KnowledgeProperties properties,
                                                 ObjectMapper objectMapper) {
        this.batchService = batchService;
        this.parser = parser;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new KnowledgeProperties() : properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(Long batchId, int expectedAttempt) {
        if (batchId == null || expectedAttempt <= 0) {
            return;
        }
        String owner = "document-batch-worker-" + UUID.randomUUID();
        Duration lease = Duration.ofSeconds(Math.max(30, properties.getDocumentBatch().getLeaseSeconds()));
        ClaimedBatch claim = batchService.claim(batchId, expectedAttempt, owner, lease);
        if (claim == null) {
            LOGGER.info("document batch claim skipped batchId={} attempt={}", batchId, expectedAttempt);
            return;
        }
        try {
            batchService.clearUnindexedDrafts(batchId, claim.fencingToken());
            ParseSummary summary = parseAndPersist(claim.batch(), claim.fencingToken());
            if (summary.pendingQuestions() > 0) {
                batchService.markWaiting(
                    batchId,
                    claim.fencingToken(),
                    summary.parsedFiles(),
                    summary.skippedFiles(),
                    summary.pendingQuestions()
                );
                return;
            }
            batchService.markPendingIndex(
                batchId,
                claim.fencingToken(),
                summary.parsedFiles(),
                summary.skippedFiles()
            );
            batchService.advanceIndex(batchId);
        } catch (RuntimeException ex) {
            batchService.markFailed(batchId, claim.fencingToken(), ex);
            LOGGER.error("document batch parse failed batchId={} attempt={} reason={}",
                batchId, expectedAttempt, ex.getMessage(), ex);
        }
    }

    protected ParseSummary parseAndPersist(ProjectDocumentBatchVO batch, long fencingToken) {
        List<StoredFile> files = batchService.storedFiles(batch.getBatchId(), fencingToken);
        int parsedFiles = 0;
        int skippedFiles = 0;
        int questions = 0;
        int processed = 0;
        for (StoredFile stored : files) {
            if ("SKIPPED".equals(stored.status()) || parser.shouldIgnore(stored.relativePath())) {
                skippedFiles++;
                processed++;
                updateProgress(batch, fencingToken, files.size(), processed, parsedFiles, skippedFiles);
                continue;
            }
            List<SourceDocument> sources = expand(stored);
            boolean parsedSource = false;
            int fileQuestions = 0;
            for (SourceDocument source : sources) {
                ParsedDocument parsed = parser.parse(new DocumentInput(
                    source.relativePath(),
                    source.originalName(),
                    source.content(),
                    source.declaredKind()
                ));
                if (parsed.ignored()) {
                    continue;
                }
                PersistedDocument persisted = persistParsed(batch, stored.fileId(), parsed);
                parsedSource = true;
                if (parsed.requiresUserConfirmation()) {
                    insertQuestion(batch, stored.fileId(), persisted.documentId(), parsed.relativePath());
                    fileQuestions++;
                    questions++;
                }
            }
            if (parsedSource) {
                parsedFiles++;
                jdbcTemplate.update(
                    "update ai_project_document_file set status = ?, updated_at = current_timestamp where file_id = ?",
                    fileQuestions > 0 ? "WAITING_CONFIRMATION" : "PARSED_PENDING_INDEX",
                    stored.fileId()
                );
            } else {
                skippedFiles++;
                jdbcTemplate.update(
                    "update ai_project_document_file set status = 'SKIPPED', updated_at = current_timestamp where file_id = ?",
                    stored.fileId()
                );
            }
            processed++;
            updateProgress(batch, fencingToken, files.size(), processed, parsedFiles, skippedFiles);
        }
        if (parsedFiles == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "document batch contains no supported text");
        }
        if (questions > 0) {
            jdbcTemplate.update(
                "update ai_project_document set status = 'WAITING_CONFIRMATION', updated_at = current_timestamp "
                    + "where batch_id = ?",
                batch.getBatchId()
            );
            jdbcTemplate.update(
                "update ai_project_document_generation set status = 'WAITING_CONFIRMATION', updated_at = current_timestamp "
                    + "where batch_id = ?",
                batch.getBatchId()
            );
        }
        return new ParseSummary(parsedFiles, skippedFiles, questions);
    }

    private void updateProgress(ProjectDocumentBatchVO batch,
                                long token,
                                int total,
                                int processed,
                                int parsed,
                                int skipped) {
        int progress = 10 + (int) Math.floor((Math.max(0, processed) * 60.0d) / Math.max(1, total));
        if (!batchService.updateProgress(batch.getBatchId(), token, progress, parsed, skipped)) {
            throw new BusinessException(ResultCode.CONFLICT, "document batch lease was lost");
        }
    }

    private List<SourceDocument> expand(StoredFile stored) {
        if (!stored.relativePath().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return List.of(new SourceDocument(
                stored.relativePath(),
                stored.originalName(),
                stored.content(),
                parseDeclaredKind(stored.declaredKind())
            ));
        }
        List<SourceDocument> result = new ArrayList<>();
        int maxEntries = Math.max(1, properties.getDocumentBatch().getMaxZipEntries());
        long maxExpanded = Math.max(1L, properties.getDocumentBatch().getMaxExpandedBytes());
        long expanded = 0L;
        int entries = 0;
        String parent = parentPath(stored.relativePath());
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(stored.content()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries++;
                if (entries > maxEntries) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "ZIP entry limit exceeded");
                }
                String relativePath = parser.normalizeRelativePath(parent + entry.getName(), entry.getName());
                if (parser.shouldIgnore(relativePath) || !isTextPath(relativePath)) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    expanded += read;
                    if (expanded > maxExpanded) {
                        throw new BusinessException(ResultCode.BAD_REQUEST, "ZIP expanded size limit exceeded");
                    }
                    output.write(buffer, 0, read);
                }
                byte[] content = output.toByteArray();
                if (content.length > properties.getProjectIngest().getMaxFileBytes()) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "ZIP text entry exceeds size limit");
                }
                result.add(new SourceDocument(
                    relativePath,
                    entry.getName(),
                    content,
                    parseDeclaredKind(stored.declaredKind())
                ));
            }
        } catch (IOException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "ZIP document could not be read");
        }
        if (result.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "ZIP contains no supported text documents");
        }
        return List.copyOf(result);
    }

    private PersistedDocument persistParsed(ProjectDocumentBatchVO batch,
                                            Long fileId,
                                            ParsedDocument parsed) {
        String contentHash = sha256(parsed.normalizedContent());
        KeyHolder documentKey = new GeneratedKeyHolder();
        String reasons = toJson(parsed.reasons());
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_document(batch_id, file_id, user_id, project_id, work_id, relative_path, "
                    + "title, document_kind, classification_confidence, classification_reasons, content_hash, "
                    + "normalized_content, status) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new String[]{"document_id"}
            );
            statement.setLong(1, batch.getBatchId());
            statement.setLong(2, fileId);
            statement.setLong(3, batch.getUserId());
            statement.setLong(4, batch.getProjectId());
            statement.setLong(5, batch.getWorkId());
            statement.setString(6, parsed.relativePath());
            statement.setString(7, title(parsed));
            statement.setString(8, parsed.kind().name());
            statement.setDouble(9, parsed.confidence());
            statement.setString(10, reasons);
            statement.setString(11, contentHash);
            statement.setString(12, parsed.normalizedContent());
            statement.setString(13, "PARSED_PENDING_INDEX");
            return statement;
        }, documentKey);
        Number generatedDocument = documentKey.getKey();
        if (generatedDocument == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "project document id missing");
        }
        long documentId = generatedDocument.longValue();
        KeyHolder generationKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project_document_generation(document_id, batch_id, user_id, project_id, work_id, "
                    + "parser_version, content_hash, status) values(?, ?, ?, ?, ?, ?, ?, 'PREPARED')",
                new String[]{"document_generation_id"}
            );
            statement.setLong(1, documentId);
            statement.setLong(2, batch.getBatchId());
            statement.setLong(3, batch.getUserId());
            statement.setLong(4, batch.getProjectId());
            statement.setLong(5, batch.getWorkId());
            statement.setString(6, properties.getProjectIngest().getParserVersion());
            statement.setString(7, contentHash);
            return statement;
        }, generationKey);
        Number generatedGeneration = generationKey.getKey();
        if (generatedGeneration == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "project document generation id missing");
        }
        long generationId = generatedGeneration.longValue();
        int ordinal = 1;
        for (DocumentSection section : parsed.sections()) {
            List<String> chunks = split(section.content(), properties.getProjectIngest().getMaxChapterChars());
            for (int index = 0; index < chunks.size(); index++) {
                String content = chunks.get(index);
                String sectionTitle = section.title();
                if (chunks.size() > 1) {
                    sectionTitle = (sectionTitle == null ? parsed.originalName() : sectionTitle)
                        + " (" + (index + 1) + "/" + chunks.size() + ")";
                }
                jdbcTemplate.update(
                    "insert into ai_project_document_section(document_id, document_generation_id, user_id, project_id, "
                        + "work_id, section_ordinal, title, section_kind, start_offset, end_offset, content_hash, content, status) "
                        + "values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PARSED_PENDING_INDEX')",
                    documentId,
                    generationId,
                    batch.getUserId(),
                    batch.getProjectId(),
                    batch.getWorkId(),
                    ordinal++,
                    trim(sectionTitle, 255),
                    section.kind().name(),
                    section.startOffset(),
                    section.endOffset(),
                    sha256(content),
                    content
                );
            }
        }
        int sectionCount = ordinal - 1;
        jdbcTemplate.update(
            "update ai_project_document_generation set section_count = ?, updated_at = current_timestamp "
                + "where document_generation_id = ?",
            sectionCount, generationId
        );
        jdbcTemplate.update(
            "update ai_project_document_file set document_id = coalesce(document_id, ?), updated_at = current_timestamp "
                + "where file_id = ?",
            documentId, fileId
        );
        return new PersistedDocument(documentId, generationId);
    }

    private void insertQuestion(ProjectDocumentBatchVO batch,
                                Long fileId,
                                long documentId,
                                String relativePath) {
        jdbcTemplate.update(
            "insert into ai_project_document_question(batch_id, file_id, document_id, user_id, project_id, work_id, "
                + "question_type, prompt, options_json, status) values(?, ?, ?, ?, ?, ?, 'DOCUMENT_KIND', ?, ?, 'PENDING')",
            batch.getBatchId(),
            fileId,
            documentId,
            batch.getUserId(),
            batch.getProjectId(),
            batch.getWorkId(),
            "请确认资料类型：" + relativePath,
            KIND_OPTIONS
        );
    }

    private List<String> split(String content, int configuredLimit) {
        int limit = Math.max(1000, configuredLimit);
        if (content.length() <= limit) {
            return List.of(content);
        }
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < content.length(); start += limit) {
            chunks.add(content.substring(start, Math.min(content.length(), start + limit)));
        }
        return List.copyOf(chunks);
    }

    private String title(ParsedDocument parsed) {
        if (!parsed.sections().isEmpty() && parsed.sections().get(0).title() != null) {
            return trim(parsed.sections().get(0).title(), 255);
        }
        return trim(parsed.originalName(), 255);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private DocumentKind parseDeclaredKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DocumentKind.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "stored project document kind is invalid");
        }
    }

    private boolean isTextPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private String sha256(String value) {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private record SourceDocument(String relativePath,
                                  String originalName,
                                  byte[] content,
                                  DocumentKind declaredKind) {
    }

    private record PersistedDocument(long documentId, long generationId) {
    }

    protected record ParseSummary(int parsedFiles, int skippedFiles, int pendingQuestions) {
    }
}

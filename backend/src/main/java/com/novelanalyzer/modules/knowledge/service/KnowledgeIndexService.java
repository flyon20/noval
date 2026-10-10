package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.asyncjob.dto.AsyncJobSubmitResponse;
import com.novelanalyzer.modules.asyncjob.service.AsyncJobService;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import com.novelanalyzer.modules.crawler.model.CrawlChapterEntity;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.model.KnowledgeChunkEntity;
import com.novelanalyzer.modules.knowledge.model.KnowledgeDocumentEntity;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class KnowledgeIndexService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexService.class);

    private static final String SOURCE_TYPE_INTRO = "INTRO";
    private static final String SOURCE_TYPE_CHAPTER = "CHAPTER";
    private static final String SOURCE_TYPE_ANALYSIS = "ANALYSIS";
    private static final String SOURCE_TYPE_RANK = "RANK";
    private static final String JOB_TYPE_INDEX_BOOK = "KNOWLEDGE_INDEX_BOOK";
    private static final long INDEX_JOB_LOCK_TTL_SECONDS = 300L;
    private static final String VECTOR_STATUS_INDEXED = "INDEXED";
    private static final String VECTOR_STATUS_PENDING = "PENDING";
    private static final String DEFAULT_CHUNK_STRATEGY_VERSION = "rag-v2";
    private static final IndexExecutionGuard PERMISSIVE_GUARD = new IndexExecutionGuard() {
        @Override
        public void checkpoint() {
        }

        @Override
        public <T> T mysqlSideEffect(Supplier<T> sideEffect) {
            return sideEffect.get();
        }

        @Override
        public String pointId(Long chunkId) {
            return String.valueOf(chunkId);
        }
    };

    private final KnowledgeRepository knowledgeRepository;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final KnowledgeProperties knowledgeProperties;
    private final AsyncJobService asyncJobService;

    public KnowledgeIndexService(KnowledgeRepository knowledgeRepository,
                                 EmbeddingClient embeddingClient,
                                 QdrantClient qdrantClient,
                                 KnowledgeProperties knowledgeProperties,
                                 AsyncJobService asyncJobService) {
        this.knowledgeRepository = knowledgeRepository;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.knowledgeProperties = knowledgeProperties;
        this.asyncJobService = asyncJobService;
    }

    public IndexResult indexBook(Long bookId) {
        return indexBook(bookId, "ALL");
    }

    public IndexResult indexBook(Long bookId, String mode) {
        return indexBook(bookId, mode, PERMISSIVE_GUARD);
    }

    public IndexResult indexBook(Long bookId,
                                 String mode,
                                 IndexExecutionGuard executionGuard) {
        IndexExecutionGuard guard = executionGuard == null ? PERMISSIVE_GUARD : executionGuard;
        guard.checkpoint();
        CrawlBookEntity book = knowledgeRepository.findBook(bookId)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "book not found"));
        guard.checkpoint();
        qdrantClient.ensureCollection();

        int createdChunks = 0;
        int indexedChunks = 0;
        String normalizedMode = mode == null ? "ALL" : mode.trim().toUpperCase();
        boolean rankOnly = "RANK_MISSING".equals(normalizedMode) || "RANK_INCREMENTAL".equals(normalizedMode);
        boolean chapterOnly = "CHAPTER_MISSING".equals(normalizedMode);

        if (!rankOnly && !chapterOnly) {
            ChunkIndexOutcome introOutcome = indexIntro(book, guard);
            createdChunks += introOutcome.createdChunks();
            indexedChunks += introOutcome.indexedChunks();
        }

        if (!chapterOnly) {
            for (KnowledgeRepository.RankEvidence rank : knowledgeRepository.findLatestRankEvidenceForBook(bookId)) {
                guard.checkpoint();
                ChunkIndexOutcome outcome = indexRank(book, rank, guard);
                createdChunks += outcome.createdChunks();
                indexedChunks += outcome.indexedChunks();
            }
        }

        if (!rankOnly) {
            List<CrawlChapterEntity> chapters = knowledgeRepository.findChapters(
                bookId,
                knowledgeProperties.getIndex().getMaxChapters()
            );
            for (CrawlChapterEntity chapter : chapters) {
                guard.checkpoint();
                ChunkIndexOutcome outcome = indexChapter(book, chapter, guard);
                createdChunks += outcome.createdChunks();
                indexedChunks += outcome.indexedChunks();
            }
        }
        return new IndexResult(bookId, createdChunks, indexedChunks);
    }

    public IndexResult indexAnalysisResult(Long analysisResultId) {
        AnalysisResultEntity analysis = knowledgeRepository.findAnalysisResult(analysisResultId)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "analysis result not found"));
        LOGGER.info("Skipping private analysis result {} for ownerless global knowledge index", analysisResultId);
        return new IndexResult(analysis.getBookId(), 0, 0);
    }

    public AsyncJobSubmitResponse submitBookIndexJob(Long bookId, Long triggerUserId) {
        return submitBookIndexJob(bookId, triggerUserId, "ALL");
    }

    public AsyncJobSubmitResponse submitBookIndexJob(Long bookId, Long triggerUserId, String mode) {
        return submitBookIndexJob(bookId, triggerUserId, mode, null);
    }

    public AsyncJobSubmitResponse submitBookIndexJob(Long bookId,
                                                     Long triggerUserId,
                                                     String mode,
                                                     String actionIdempotencyKey) {
        String normalizedMode = normalizeIndexMode(mode);
        String jobKey = buildJobKey(bookId, normalizedMode, actionIdempotencyKey);
        String resourceKey = "book:" + bookId;
        String requestJson = "{\"bookId\":" + bookId + ",\"mode\":\"" + normalizedMode + "\"}";
        if (actionIdempotencyKey == null || actionIdempotencyKey.isBlank()) {
            return asyncJobService.submitOrReuse(
                JOB_TYPE_INDEX_BOOK,
                jobKey,
                resourceKey,
                requestJson,
                triggerUserId,
                INDEX_JOB_LOCK_TTL_SECONDS
            );
        }
        return asyncJobService.submitOrReuseSuccessful(
            JOB_TYPE_INDEX_BOOK,
            jobKey,
            resourceKey,
            requestJson,
            triggerUserId,
            INDEX_JOB_LOCK_TTL_SECONDS
        );
    }

    public AsyncJobSubmitResponse submitBookIndexPendingJob(Long bookId, Long triggerUserId) {
        return submitBookIndexPendingJob(bookId, triggerUserId, "ALL");
    }

    public AsyncJobSubmitResponse submitBookIndexPendingJob(Long bookId, Long triggerUserId, String mode) {
        return submitBookIndexPendingJob(bookId, triggerUserId, mode, null);
    }

    public AsyncJobSubmitResponse submitBookIndexPendingJob(Long bookId,
                                                            Long triggerUserId,
                                                            String mode,
                                                            String actionIdempotencyKey) {
        String normalizedMode = normalizeIndexMode(mode);
        String jobKey = buildJobKey(bookId, normalizedMode, actionIdempotencyKey);
        String resourceKey = "book:" + bookId;
        String requestJson = "{\"bookId\":" + bookId + ",\"mode\":\"" + normalizedMode + "\"}";
        if (actionIdempotencyKey == null || actionIdempotencyKey.isBlank()) {
            return asyncJobService.submitOrReusePending(
                JOB_TYPE_INDEX_BOOK,
                jobKey,
                resourceKey,
                requestJson,
                triggerUserId,
                INDEX_JOB_LOCK_TTL_SECONDS
            );
        }
        return asyncJobService.submitOrReuseSuccessfulPending(
            JOB_TYPE_INDEX_BOOK,
            jobKey,
            resourceKey,
            requestJson,
            triggerUserId,
            INDEX_JOB_LOCK_TTL_SECONDS
        );
    }

    private String normalizeIndexMode(String mode) {
        String normalized = mode == null ? "ALL" : mode.trim().toUpperCase();
        if (!"ALL".equals(normalized)
            && !"FAILED_ONLY".equals(normalized)
            && !"FULL_REINDEX".equals(normalized)
            && !"RANK_MISSING".equals(normalized)
            && !"RANK_INCREMENTAL".equals(normalized)
            && !"CHAPTER_MISSING".equals(normalized)) {
            return "ALL";
        }
        return normalized;
    }

    private String buildJobKey(Long bookId, String normalizedMode) {
        return buildJobKey(bookId, normalizedMode, null);
    }

    private String buildJobKey(Long bookId,
                               String normalizedMode,
                               String actionIdempotencyKey) {
        String jobKey = "book:" + bookId;
        if (!"ALL".equals(normalizedMode)) {
            if ("FULL_REINDEX".equals(normalizedMode)) {
                jobKey += ":" + normalizedMode + ":" + currentEmbeddingModel() + ":" + currentEmbeddingDimension();
            } else {
                jobKey += ":" + normalizedMode;
            }
        }
        if (actionIdempotencyKey == null || actionIdempotencyKey.isBlank()) {
            return jobKey;
        }
        return jobKey + ":action:" + sha256(actionIdempotencyKey.trim());
    }

    private ChunkIndexOutcome indexIntro(CrawlBookEntity book, IndexExecutionGuard guard) {
        String intro = normalizeText(book.getIntro());
        if (intro.isEmpty()) {
            return new ChunkIndexOutcome(0, 0);
        }
        KnowledgeDocumentEntity document = guard.mysqlSideEffect(() -> knowledgeRepository.saveOrUpdateDocument(
            SOURCE_TYPE_INTRO,
            book.getId(),
            book.getPlatform(),
            book.getId(),
            book.getBookName() + " \u7b80\u4ecb"
        ));
        String chunkText = buildHeader(book, SOURCE_TYPE_INTRO, null, null) + intro;
        return persistAndVectorizeChunk(
            document, book, SOURCE_TYPE_INTRO, book.getId(), null, null, "intro-1", chunkText, guard
        );
    }

    private ChunkIndexOutcome indexRank(CrawlBookEntity book,
                                        KnowledgeRepository.RankEvidence rank,
                                        IndexExecutionGuard guard) {
        KnowledgeDocumentEntity document = guard.mysqlSideEffect(() -> knowledgeRepository.saveOrUpdateDocument(
            SOURCE_TYPE_RANK,
            rank.id(),
            defaultText(rank.platform()),
            book.getId(),
            rankTitle(rank)
        ));
        String chunkText = buildHeader(book, SOURCE_TYPE_RANK, null, null)
            + "\u699c\u5355\uff1a" + rankBoardText(rank) + '\n'
            + "\u699c\u5355\u6807\u8bc6\uff1a" + defaultText(rank.channelCode()) + " / " + defaultText(rank.boardCode()) + '\n'
            + "\u6392\u540d\uff1a\u7b2c" + defaultRankNo(rank.rankNo()) + "\u540d" + '\n'
            + "\u5feb\u7167\u65f6\u95f4\uff1a" + defaultTime(rank.snapshotTime(), rank.crawlTime()) + '\n'
            + "\u4e66\u540d\uff1a" + defaultText(rank.bookName()) + '\n'
            + "\u4f5c\u8005\uff1a" + defaultText(rank.author()) + '\n'
            + "\u7b80\u4ecb\uff1a" + defaultText(rank.intro());
        Map<String, Object> extraPayload = new LinkedHashMap<>();
        if (rank.rankNo() != null) {
            extraPayload.put("rankNo", rank.rankNo());
        }
        if (rank.channelCode() != null && !rank.channelCode().isBlank()) {
            extraPayload.put("channelCode", rank.channelCode());
        }
        if (rank.boardCode() != null && !rank.boardCode().isBlank()) {
            extraPayload.put("boardCode", rank.boardCode());
        }
        if (rank.snapshotId() != null) {
            extraPayload.put("snapshotId", rank.snapshotId());
        }
        return persistAndVectorizeChunk(
            document,
            book,
            SOURCE_TYPE_RANK,
            rank.id(),
            null,
            null,
            "rank-" + rank.id(),
            chunkText,
            extraPayload,
            guard
        );
    }

    private ChunkIndexOutcome indexChapter(CrawlBookEntity book,
                                           CrawlChapterEntity chapter,
                                           IndexExecutionGuard guard) {
        String content = normalizeText(chapter.getContent());
        if (content.isEmpty()) {
            return new ChunkIndexOutcome(0, 0);
        }
        KnowledgeDocumentEntity document = guard.mysqlSideEffect(() -> knowledgeRepository.saveOrUpdateDocument(
            SOURCE_TYPE_CHAPTER,
            chapter.getId(),
            book.getPlatform(),
            book.getId(),
            chapter.getChapterTitle()
        ));
        List<String> contentChunks = splitParagraphAware(content, resolveChunkTargetChars(), resolveChunkOverlapChars());
        int createdChunks = 0;
        int indexedChunks = 0;
        for (int index = 0; index < contentChunks.size(); index++) {
            guard.checkpoint();
            String chunkText = buildHeader(book, SOURCE_TYPE_CHAPTER, chapter.getChapterNo(), chapter.getChapterTitle()) + contentChunks.get(index);
            ChunkIndexOutcome outcome = persistAndVectorizeChunk(
                document,
                book,
                SOURCE_TYPE_CHAPTER,
                chapter.getId(),
                chapter.getChapterNo(),
                null,
                "chapter-" + chapter.getChapterNo() + "-" + (index + 1),
                chunkText,
                guard
            );
            createdChunks += outcome.createdChunks();
            indexedChunks += outcome.indexedChunks();
        }
        return new ChunkIndexOutcome(createdChunks, indexedChunks);
    }

    private List<String> splitParagraphAware(String content, int targetChars, int overlapChars) {
        String normalized = normalizeText(content);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() <= targetChars) {
            return List.of(normalized);
        }
        List<String> chunks = new ArrayList<>();
        List<String> paragraphs = splitParagraphs(normalized);
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.length() > targetChars) {
                flushChunk(chunks, current);
                splitLongParagraph(paragraph, targetChars, overlapChars, chunks);
                continue;
            }
            int separatorLength = current.isEmpty() ? 0 : 2;
            if (!current.isEmpty() && current.length() + separatorLength + paragraph.length() > targetChars) {
                flushChunk(chunks, current);
                current.append(overlapTail(chunks.get(chunks.size() - 1), overlapChars));
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        flushChunk(chunks, current);
        return chunks;
    }

    private List<String> splitParagraphs(String content) {
        String[] rawParagraphs = content.split("(?:\\r?\\n){2,}");
        List<String> paragraphs = new ArrayList<>();
        for (String rawParagraph : rawParagraphs) {
            String paragraph = rawParagraph.trim();
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
            }
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(content);
        }
        return paragraphs;
    }

    private void splitLongParagraph(String paragraph, int targetChars, int overlapChars, List<String> chunks) {
        int start = 0;
        while (start < paragraph.length()) {
            int maxEnd = Math.min(paragraph.length(), start + targetChars);
            int end = findSentenceBoundary(paragraph, start, maxEnd);
            chunks.add(paragraph.substring(start, end));
            if (end >= paragraph.length()) {
                break;
            }
            start = Math.max(end - overlapChars, start + 1);
        }
    }

    private int findSentenceBoundary(String text, int start, int maxEnd) {
        if (maxEnd >= text.length()) {
            return text.length();
        }
        int minimumEnd = start + Math.max(1, (int) Math.floor((maxEnd - start) * 0.65));
        for (int index = maxEnd - 1; index >= minimumEnd; index--) {
            if (isSentenceTerminator(text.charAt(index))) {
                return index + 1;
            }
        }
        return maxEnd;
    }

    private boolean isSentenceTerminator(char value) {
        return Set.of('\u3002', '\uff01', '\uff1f', '\uff1b', '.', '!', '?', ';').contains(value);
    }

    private void flushChunk(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }

    private String overlapTail(String text, int overlapChars) {
        if (text.length() <= overlapChars) {
            return text;
        }
        return text.substring(text.length() - overlapChars);
    }

    private ChunkIndexOutcome persistAndVectorizeChunk(KnowledgeDocumentEntity document,
                                                       CrawlBookEntity book,
                                                       String sourceType,
                                                       Long sourceRefId,
                                                       Integer chapterNo,
                                                       String analysisType,
                                                       String chunkKey,
                                                       String chunkText) {
        return persistAndVectorizeChunk(
            document, book, sourceType, sourceRefId, chapterNo, analysisType, chunkKey, chunkText,
            Map.of(), PERMISSIVE_GUARD
        );
    }

    private ChunkIndexOutcome persistAndVectorizeChunk(KnowledgeDocumentEntity document,
                                                       CrawlBookEntity book,
                                                       String sourceType,
                                                       Long sourceRefId,
                                                       Integer chapterNo,
                                                       String analysisType,
                                                       String chunkKey,
                                                       String chunkText,
                                                       IndexExecutionGuard guard) {
        return persistAndVectorizeChunk(
            document, book, sourceType, sourceRefId, chapterNo, analysisType, chunkKey, chunkText,
            Map.of(), guard
        );
    }

    private ChunkIndexOutcome persistAndVectorizeChunk(KnowledgeDocumentEntity document,
                                                       CrawlBookEntity book,
                                                       String sourceType,
                                                       Long sourceRefId,
                                                       Integer chapterNo,
                                                       String analysisType,
                                                       String chunkKey,
                                                       String chunkText,
                                                       Map<String, Object> extraPayload) {
        return persistAndVectorizeChunk(
            document, book, sourceType, sourceRefId, chapterNo, analysisType, chunkKey, chunkText,
            extraPayload, PERMISSIVE_GUARD
        );
    }

    private ChunkIndexOutcome persistAndVectorizeChunk(KnowledgeDocumentEntity document,
                                                       CrawlBookEntity book,
                                                       String sourceType,
                                                       Long sourceRefId,
                                                       Integer chapterNo,
                                                       String analysisType,
                                                       String chunkKey,
                                                       String chunkText,
                                                       Map<String, Object> extraPayload,
                                                       IndexExecutionGuard guard) {
        guard.checkpoint();
        String contentHash = sha256(chunkText);
        KnowledgeChunkEntity existing = knowledgeRepository.findChunk(document.getId(), chunkKey).orElse(null);
        if (existing != null
            && contentHash.equals(existing.getContentHash())
            && currentChunkStrategyVersion().equals(existing.getChunkStrategyVersion())
            && currentEmbeddingModel().equals(existing.getEmbeddingModel())
            && Integer.valueOf(currentEmbeddingDimension()).equals(existing.getEmbeddingDimension())
            && VECTOR_STATUS_INDEXED.equals(existing.getVectorStatus())) {
            return new ChunkIndexOutcome(0, 0);
        }

        KnowledgeChunkEntity chunk = existing == null ? new KnowledgeChunkEntity() : existing;
        chunk.setDocumentId(document.getId());
        chunk.setChunkKey(chunkKey);
        chunk.setSourceType(sourceType);
        chunk.setSourceRefId(sourceRefId);
        chunk.setBookId(book.getId());
        chunk.setChapterNo(chapterNo);
        chunk.setAnalysisType(analysisType);
        chunk.setContentHash(contentHash);
        chunk.setChunkText(chunkText);
        chunk.setTokenCount(estimateTokenCount(chunkText));
        chunk.setChunkStrategyVersion(currentChunkStrategyVersion());
        chunk.setEmbeddingModel(currentEmbeddingModel());
        chunk.setEmbeddingDimension(currentEmbeddingDimension());
        chunk.setVectorStatus(VECTOR_STATUS_PENDING);
        guard.mysqlSideEffect(() -> {
            if (existing == null) {
                knowledgeRepository.saveChunk(chunk);
            } else {
                knowledgeRepository.updateChunkForReindex(chunk);
            }
            return chunk;
        });

        guard.checkpoint();
        LOGGER.info("knowledge index before embed: bookId={}, chunkId={}, chunkKey={}, sourceType={}",
            book.getId(),
            chunk.getId(),
            chunk.getChunkKey(),
            chunk.getSourceType());
        List<Double> embedding = embeddingClient.embed(chunkText);
        guard.checkpoint();
        LOGGER.info("knowledge index after embed: bookId={}, chunkId={}, vectorSize={}",
            book.getId(),
            chunk.getId(),
            embedding == null ? 0 : embedding.size());
        String pointId = guard.pointId(chunk.getId());
        Map<String, Object> payload = buildPayload(book, chunk, extraPayload);
        payload.putAll(guard.payloadMetadata());
        LOGGER.info("knowledge index before qdrant upsert: bookId={}, chunkId={}, pointId={}, payloadKeys={}, payloadSize={}",
            book.getId(),
            chunk.getId(),
            pointId,
            payload.keySet(),
            payload.size());
        qdrantClient.upsertPoint(pointId, embedding, payload);
        guard.checkpoint();
        LOGGER.info("knowledge index after qdrant upsert: bookId={}, chunkId={}, pointId={}",
            book.getId(),
            chunk.getId(),
            pointId);
        guard.mysqlSideEffect(() -> {
            knowledgeRepository.updateChunkVectorStatus(chunk, VECTOR_STATUS_INDEXED, pointId);
            return chunk;
        });
        LOGGER.info("knowledge index after status update: bookId={}, chunkId={}, pointId={}",
            book.getId(),
            chunk.getId(),
            pointId);
        return new ChunkIndexOutcome(existing == null ? 1 : 0, 1);
    }

    public interface IndexExecutionGuard {
        void checkpoint();

        <T> T mysqlSideEffect(Supplier<T> sideEffect);

        String pointId(Long chunkId);

        default Map<String, Object> payloadMetadata() {
            return Map.of();
        }
    }

    private Map<String, Object> buildPayload(CrawlBookEntity book, KnowledgeChunkEntity chunk, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkId", chunk.getId());
        payload.put("documentId", chunk.getDocumentId());
        payload.put("bookId", book.getId());
        payload.put("platform", book.getPlatform());
        payload.put("sourceType", chunk.getSourceType());
        payload.put("sourceRefId", chunk.getSourceRefId());
        payload.put("chunkStrategyVersion", chunk.getChunkStrategyVersion());
        payload.put("embeddingModel", chunk.getEmbeddingModel());
        payload.put("embeddingDimension", chunk.getEmbeddingDimension());
        if (chunk.getChapterNo() != null) {
            payload.put("chapterNo", chunk.getChapterNo());
        }
        if (chunk.getAnalysisType() != null && !chunk.getAnalysisType().isBlank()) {
            payload.put("analysisType", chunk.getAnalysisType());
        }
        if (extraPayload != null && !extraPayload.isEmpty()) {
            payload.putAll(extraPayload);
        }
        return payload;
    }

    private String rankTitle(KnowledgeRepository.RankEvidence rank) {
        return rankBoardText(rank) + " #" + defaultRankNo(rank.rankNo());
    }

    private String rankBoardText(KnowledgeRepository.RankEvidence rank) {
        String channel = firstNonBlank(rank.channelName(), rank.channelCode(), rank.category());
        String board = firstNonBlank(rank.boardName(), rank.category(), rank.boardCode());
        return defaultText(channel) + " / " + defaultText(board);
    }

    private String defaultRankNo(Integer rankNo) {
        return rankNo == null ? "\u672a\u77e5" : String.valueOf(rankNo);
    }

    private String defaultTime(java.time.LocalDateTime preferred, java.time.LocalDateTime fallback) {
        java.time.LocalDateTime value = preferred == null ? fallback : preferred;
        return value == null ? "\u672a\u77e5" : value.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String buildHeader(CrawlBookEntity book, String sourceType, Integer chapterNo, String chapterTitle) {
        StringBuilder builder = new StringBuilder();
        builder.append("\u4e66\u540d\uff1a").append(defaultText(book.getBookName())).append('\n');
        builder.append("\u4f5c\u8005\uff1a").append(defaultText(book.getAuthor())).append('\n');
        builder.append("\u6765\u6e90\uff1a").append(defaultText(book.getPlatform())).append('\n');
        builder.append("\u7c7b\u578b\uff1a").append(sourceType).append('\n');
        if (chapterNo != null) {
            builder.append("\u7ae0\u8282\uff1a\u7b2c").append(chapterNo).append("\u7ae0 ").append(defaultText(chapterTitle)).append('\n');
        }
        builder.append('\n');
        return builder.toString();
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }

    private String currentChunkStrategyVersion() {
        return DEFAULT_CHUNK_STRATEGY_VERSION;
    }

    private String currentEmbeddingModel() {
        String model = knowledgeProperties.getEmbedding() == null ? null : knowledgeProperties.getEmbedding().getModel();
        return model == null || model.isBlank() ? "unknown" : model.trim();
    }

    private int currentEmbeddingDimension() {
        return knowledgeProperties.getEmbedding() == null ? 0 : knowledgeProperties.getEmbedding().getDimension();
    }

    private int resolveChunkTargetChars() {
        int configured = knowledgeProperties.getIndex() == null ? 1000 : knowledgeProperties.getIndex().getChunkTargetChars();
        return Math.max(300, configured);
    }

    private int resolveChunkOverlapChars() {
        int configured = knowledgeProperties.getIndex() == null ? 160 : knowledgeProperties.getIndex().getChunkOverlapChars();
        int maxOverlap = Math.max(20, resolveChunkTargetChars() / 3);
        return Math.max(0, Math.min(configured, maxOverlap));
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "\u672a\u77e5" : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "content hash failed");
        }
    }

    public record IndexResult(Long bookId, int createdChunks, int indexedChunks) {
    }

    private record ChunkIndexOutcome(int createdChunks, int indexedChunks) {
    }
}

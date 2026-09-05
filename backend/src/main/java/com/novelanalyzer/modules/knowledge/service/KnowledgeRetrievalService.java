package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import com.novelanalyzer.modules.knowledge.client.QdrantClient;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeSearchRequest;
import com.novelanalyzer.modules.knowledge.repository.KnowledgeRepository;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeSearchResultVO;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class KnowledgeRetrievalService {

    private static final double DEFAULT_MIN_SCORE = 0.2d;
    private static final String SOURCE_TYPE_ANALYSIS = "ANALYSIS";

    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final KnowledgeRepository knowledgeRepository;

    public KnowledgeRetrievalService(EmbeddingClient embeddingClient,
                                     QdrantClient qdrantClient,
                                     KnowledgeRepository knowledgeRepository) {
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.knowledgeRepository = knowledgeRepository;
    }

    public List<KnowledgeSearchResultVO> search(KnowledgeSearchRequest request) {
        requireUserScope(request.getUserId());
        if (isAnalysisSearch(request.getSourceType())) {
            return List.of();
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("requestedMinScore", effectiveMinScore(request.getMinScore()));
        try {
            List<Double> queryVector = embeddingClient.embed(request.getQuery().trim());
            qdrantClient.ensureCollection();
            List<QdrantClient.SearchResult> qdrantResults = qdrantClient.search(
                queryVector,
                buildFilters(request),
                normalizeLimit(request.getLimit())
            );
            diagnostics.put("qdrantReturnedCount", qdrantResults.size());
            List<KnowledgeSearchResultVO> results = qdrantResults.stream()
                .filter(result -> meetsMinScore(result, effectiveMinScore(request.getMinScore())))
                .map(result -> knowledgeRepository.findSearchResultSource(resolveChunkId(result.payload()), result.id(), result.score()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
            if (!results.isEmpty()) {
                return markBackend(results, "qdrant", diagnostics);
            }
        } catch (RuntimeException ex) {
            // External embedding/vector services can fail independently of local indexed data.
            // Keep Q&A usable by falling back to lightweight lexical retrieval.
            diagnostics.put("qdrantFailureClass", ex.getClass().getSimpleName());
        }
        List<KnowledgeSearchResultVO> lexicalResults = knowledgeRepository.findLexicalSearchResults(
            request.getQuery(),
            request.getBookId(),
            request.getPlatform(),
            request.getSourceType(),
            request.getChapterNo(),
            request.getAnalysisType(),
            normalizeLimit(request.getLimit())
        );
        if (!lexicalResults.isEmpty()) {
            return markBackend(lexicalResults, "lexical", diagnostics);
        }
        return fallbackToCrawledChapters(request, diagnostics);
    }

    private List<KnowledgeSearchResultVO> fallbackToCrawledChapters(KnowledgeSearchRequest request,
                                                                    Map<String, Object> diagnostics) {
        if (request.getBookId() == null || !isChapterSearch(request.getSourceType())) {
            return List.of();
        }
        return markBackend(knowledgeRepository.findCrawledChapterSources(
            request.getBookId(),
            request.getPlatform(),
            request.getChapterNo(),
            normalizeLimit(request.getLimit())
        ), "crawler_fallback", diagnostics);
    }

    private List<KnowledgeSearchResultVO> markBackend(List<KnowledgeSearchResultVO> results,
                                                      String retrievalBackend,
                                                      Map<String, Object> diagnostics) {
        Map<String, Object> resultDiagnostics = new LinkedHashMap<>(diagnostics);
        resultDiagnostics.put("retrievalBackend", retrievalBackend);
        resultDiagnostics.put("returnedCount", results.size());
        if (!"qdrant".equals(retrievalBackend)) {
            resultDiagnostics.put("fallbackBackend", retrievalBackend);
        }
        for (KnowledgeSearchResultVO result : results) {
            result.setRetrievalBackend(retrievalBackend);
            result.setRetrievalDiagnostics(resultDiagnostics);
        }
        return results;
    }

    private boolean isChapterSearch(String sourceType) {
        return sourceType != null && "CHAPTER".equalsIgnoreCase(sourceType.trim());
    }

    private boolean isAnalysisSearch(String sourceType) {
        return sourceType != null && SOURCE_TYPE_ANALYSIS.equalsIgnoreCase(sourceType.trim());
    }

    private void requireUserScope(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "user scope required");
        }
    }

    private boolean meetsMinScore(QdrantClient.SearchResult result, Double minScore) {
        return minScore == null || result.score() >= minScore;
    }

    private Double effectiveMinScore(Double requestedMinScore) {
        return requestedMinScore == null ? DEFAULT_MIN_SCORE : requestedMinScore;
    }

    private Map<String, Object> buildFilters(KnowledgeSearchRequest request) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (request.getBookId() != null) {
            filters.put("bookId", request.getBookId());
        }
        putIfText(filters, "platform", request.getPlatform());
        putIfText(filters, "sourceType", request.getSourceType());
        if (request.getChapterNo() != null) {
            filters.put("chapterNo", request.getChapterNo());
        }
        putIfText(filters, "analysisType", request.getAnalysisType());
        return filters;
    }

    private Long resolveChunkId(Map<String, Object> payload) {
        if (payload == null || payload.get("chunkId") == null) {
            return null;
        }
        Object value = payload.get("chunkId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void putIfText(Map<String, Object> filters, String key, String value) {
        if (value != null && !value.isBlank()) {
            filters.put(key, value.trim());
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 5;
        }
        return Math.min(Math.max(limit, 1), 20);
    }
}

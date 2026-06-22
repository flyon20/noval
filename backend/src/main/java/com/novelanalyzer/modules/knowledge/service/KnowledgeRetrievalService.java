package com.novelanalyzer.modules.knowledge.service;

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
        try {
            List<Double> queryVector = embeddingClient.embed(request.getQuery().trim());
            qdrantClient.ensureCollection();
            List<QdrantClient.SearchResult> qdrantResults = qdrantClient.search(
                queryVector,
                buildFilters(request),
                normalizeLimit(request.getLimit())
            );
            List<KnowledgeSearchResultVO> results = qdrantResults.stream()
                .filter(result -> meetsMinScore(result, effectiveMinScore(request.getMinScore())))
                .map(result -> knowledgeRepository.findSearchResultSource(resolveChunkId(result.payload()), result.id(), result.score()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
            if (!results.isEmpty()) {
                return results;
            }
        } catch (RuntimeException ex) {
            // External embedding/vector services can fail independently of local indexed data.
            // Keep Q&A usable by falling back to lightweight lexical retrieval.
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
            return lexicalResults;
        }
        return fallbackToCrawledChapters(request);
    }

    private List<KnowledgeSearchResultVO> fallbackToCrawledChapters(KnowledgeSearchRequest request) {
        if (request.getBookId() == null || !isChapterSearch(request.getSourceType())) {
            return List.of();
        }
        return knowledgeRepository.findCrawledChapterSources(
            request.getBookId(),
            request.getPlatform(),
            request.getChapterNo(),
            normalizeLimit(request.getLimit())
        );
    }

    private boolean isChapterSearch(String sourceType) {
        return sourceType != null && "CHAPTER".equalsIgnoreCase(sourceType.trim());
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

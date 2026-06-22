package com.novelanalyzer.modules.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.analysis.model.AnalysisResultEntity;
import com.novelanalyzer.modules.analysis.mapper.AnalysisResultMapper;
import com.novelanalyzer.modules.crawler.mapper.CrawlBookMapper;
import com.novelanalyzer.modules.crawler.model.CrawlBookEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AnalysisHistorySearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisHistorySearchIndexService.class);
    private static final int MAX_SEARCH_TEXT_LENGTH = 120_000;

    private final AnalysisResultMapper analysisResultMapper;
    private final CrawlBookMapper crawlBookMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisHistorySearchIndexService(AnalysisResultMapper analysisResultMapper,
                                             CrawlBookMapper crawlBookMapper,
                                             JdbcTemplate jdbcTemplate,
                                             ObjectMapper objectMapper) {
        this.analysisResultMapper = analysisResultMapper;
        this.crawlBookMapper = crawlBookMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void indexResultAsyncSafe(Long analysisResultId) {
        if (analysisResultId == null) {
            return;
        }
        try {
            indexResult(analysisResultId);
        } catch (Exception ex) {
            log.warn("history search index upsert failed: analysisResultId={}", analysisResultId, ex);
        }
    }

    public void indexResult(Long analysisResultId) {
        AnalysisResultEntity result = analysisResultMapper.selectById(analysisResultId);
        if (result == null || (result.getDeleted() != null && result.getDeleted() != 0)) {
            return;
        }
        CrawlBookEntity book = result.getBookId() == null ? null : crawlBookMapper.selectById(result.getBookId());
        String bookName = book == null ? null : book.getBookName();
        String searchText = truncate(buildSearchText(result, book), MAX_SEARCH_TEXT_LENGTH);
        String structuredTerms = truncate(buildStructuredTerms(result, book), 20_000);
        int updated = jdbcTemplate.update(
            """
                UPDATE analysis_result_search_doc
                SET user_id = ?,
                    platform = ?,
                    book_id = ?,
                    book_name = ?,
                    analysis_type = ?,
                    channel_code = ?,
                    board_code = ?,
                    chapter_count = ?,
                    model_name = ?,
                    search_text = ?,
                    structured_terms = ?,
                    update_time = CURRENT_TIMESTAMP,
                    deleted = 0
                WHERE analysis_result_id = ?
                """,
            result.getUserId(),
            result.getPlatform(),
            result.getBookId(),
            bookName,
            result.getAnalysisType(),
            result.getChannelCode(),
            result.getBoardCode(),
            result.getChapterCount(),
            result.getModelName(),
            searchText,
            structuredTerms,
            result.getId()
        );
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update(
            """
                INSERT INTO analysis_result_search_doc
                    (analysis_result_id, user_id, platform, book_id, book_name, analysis_type, channel_code, board_code,
                     chapter_count, model_name, search_text, structured_terms, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """,
            result.getId(),
            result.getUserId(),
            result.getPlatform(),
            result.getBookId(),
            bookName,
            result.getAnalysisType(),
            result.getChannelCode(),
            result.getBoardCode(),
            result.getChapterCount(),
            result.getModelName(),
            searchText,
            structuredTerms
        );
    }

    private String buildSearchText(AnalysisResultEntity result, CrawlBookEntity book) {
        List<String> parts = new ArrayList<>();
        add(parts, result.getPlatform());
        add(parts, result.getAnalysisType());
        add(parts, result.getChannelCode());
        add(parts, result.getBoardCode());
        add(parts, result.getModelName());
        if (book != null) {
            add(parts, book.getBookName());
            add(parts, book.getAuthor());
            add(parts, book.getIntro());
        }
        add(parts, result.getResultContent());
        appendJsonValues(parts, readJson(result.getResultJson()), 0);
        return normalize(String.join("\n", parts));
    }

    private String buildStructuredTerms(AnalysisResultEntity result, CrawlBookEntity book) {
        List<String> parts = new ArrayList<>();
        add(parts, "platform:" + result.getPlatform());
        add(parts, "analysisType:" + result.getAnalysisType());
        add(parts, "channel:" + result.getChannelCode());
        add(parts, "board:" + result.getBoardCode());
        add(parts, "model:" + result.getModelName());
        if (book != null) {
            add(parts, "book:" + book.getBookName());
            add(parts, "author:" + book.getAuthor());
        }
        appendJsonValues(parts, readJson(result.getResultJson()), 0);
        return normalize(String.join("\n", parts));
    }

    @SuppressWarnings("unchecked")
    private void appendJsonValues(List<String> parts, Object value, int depth) {
        if (value == null || depth > 4) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null) {
                    add(parts, String.valueOf(key));
                }
                appendJsonValues(parts, item, depth + 1);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> appendJsonValues(parts, item, depth + 1));
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            add(parts, String.valueOf(value));
        }
    }

    private Map<String, Object> readJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(resultJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of("raw", resultJson);
        }
    }

    private void add(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}

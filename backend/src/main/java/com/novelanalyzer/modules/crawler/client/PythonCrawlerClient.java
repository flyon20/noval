package com.novelanalyzer.modules.crawler.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.CrawlerProperties;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankBoard;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.client.model.PythonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PythonCrawlerClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonCrawlerClient.class);
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Service-Token";

    private final RestTemplate crawlerRestTemplate;
    private final CrawlerProperties crawlerProperties;
    private final ObjectMapper objectMapper;

    public PythonCrawlerClient(RestTemplate crawlerRestTemplate,
                               CrawlerProperties crawlerProperties,
                               ObjectMapper objectMapper) {
        this.crawlerRestTemplate = crawlerRestTemplate;
        this.crawlerProperties = crawlerProperties;
        this.objectMapper = objectMapper;
    }

    public List<ExternalRankItem> fetchRank(String platform, String category) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("platform", platform);
            request.put("category", category);
            PythonResult result = crawlerRestTemplate.postForEntity(
                crawlerProperties.getBaseUrl() + "/internal/rank",
                buildRequestEntity(request),
                PythonResult.class
            ).getBody();
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler rank call failed");
            }
            return objectMapper.convertValue(result.getData(), new TypeReference<List<ExternalRankItem>>() {
            });
        } catch (Exception ex) {
            throw propagateCrawlerFailure("crawler rank call failed", ex);
        }
    }

    public List<ExternalRankItem> fetchRank(String platform, String channelCode, String boardCode) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("platform", platform);
            request.put("channelCode", channelCode);
            request.put("boardCode", boardCode);
            PythonResult result = crawlerRestTemplate.postForEntity(
                crawlerProperties.getBaseUrl() + "/internal/rank",
                buildRequestEntity(request),
                PythonResult.class
            ).getBody();
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler rank call failed");
            }
            return objectMapper.convertValue(result.getData(), new TypeReference<List<ExternalRankItem>>() {
            });
        } catch (Exception ex) {
            throw propagateCrawlerFailure("crawler board rank call failed", ex);
        }
    }

    public List<ExternalRankBoard> fetchBoardCatalog(String platform) {
        try {
            String url = UriComponentsBuilder
                .fromHttpUrl(crawlerProperties.getBaseUrl() + "/internal/board-catalog")
                .queryParam("platform", platform)
                .toUriString();
            PythonResult result = crawlerRestTemplate.exchange(
                url,
                HttpMethod.GET,
                buildRequestEntity(null),
                PythonResult.class
            ).getBody();
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler board catalog call failed");
            }
            return flattenBoardCatalog(platform, result.getData());
        } catch (Exception ex) {
            throw propagateCrawlerFailure("crawler board catalog call failed", ex);
        }
    }

    public ExternalBookDetail fetchBook(String platform, String bookUrl) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("platform", platform);
            request.put("bookUrl", bookUrl);
            PythonResult result = crawlerRestTemplate.postForEntity(
                crawlerProperties.getBaseUrl() + "/internal/book",
                buildRequestEntity(request),
                PythonResult.class
            ).getBody();
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler book call failed");
            }
            return objectMapper.convertValue(result.getData(), ExternalBookDetail.class);
        } catch (Exception ex) {
            throw propagateCrawlerFailure("crawler book call failed", ex);
        }
    }

    public List<ExternalChapterItem> fetchChapters(String platform, String bookUrl, Integer chapterCount) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("platform", platform);
            request.put("bookUrl", bookUrl);
            request.put("chapterCount", chapterCount);
            PythonResult result = crawlerRestTemplate.postForEntity(
                crawlerProperties.getBaseUrl() + "/internal/chapters",
                buildRequestEntity(request),
                PythonResult.class
            ).getBody();
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler chapter call failed");
            }
            return objectMapper.convertValue(result.getData(), new TypeReference<List<ExternalChapterItem>>() {
            });
        } catch (Exception ex) {
            throw propagateCrawlerFailure("crawler chapter call failed", ex);
        }
    }

    private HttpEntity<Map<String, Object>> buildRequestEntity(Map<String, Object> request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(INTERNAL_API_KEY_HEADER, crawlerProperties.getInternalApiKey());
        return new HttpEntity<>(request, headers);
    }

    private List<ExternalRankBoard> flattenBoardCatalog(String platform, Object payload) {
        List<Map<String, Object>> channels = objectMapper.convertValue(payload, new TypeReference<List<Map<String, Object>>>() {
        });
        List<ExternalRankBoard> boards = new java.util.ArrayList<>();
        for (Map<String, Object> channel : channels) {
            String channelCode = asString(channel.get("channelCode"));
            String channelName = asString(channel.get("channelName"));
            List<Map<String, Object>> boardItems = objectMapper.convertValue(
                channel.get("boards"),
                new TypeReference<List<Map<String, Object>>>() {
                }
            );
            for (Map<String, Object> boardItem : boardItems) {
                ExternalRankBoard board = new ExternalRankBoard();
                board.setPlatform(platform);
                board.setChannelCode(channelCode);
                board.setChannelName(channelName);
                board.setBoardCode(asString(boardItem.get("boardCode")));
                board.setBoardName(asString(boardItem.get("boardName")));
                boards.add(board);
            }
        }
        return boards;
    }

    private RuntimeException propagateCrawlerFailure(String message, Exception ex) {
        LOGGER.warn("{}: {}", message, ex.getMessage());
        if (ex instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new BusinessException(ResultCode.INTERNAL_ERROR, message);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

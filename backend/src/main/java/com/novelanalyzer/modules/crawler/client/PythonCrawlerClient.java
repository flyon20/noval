package com.novelanalyzer.modules.crawler.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.CrawlerProperties;
import com.novelanalyzer.modules.crawler.client.model.ExternalBookDetail;
import com.novelanalyzer.modules.crawler.client.model.ExternalChapterItem;
import com.novelanalyzer.modules.crawler.client.model.ExternalRankItem;
import com.novelanalyzer.modules.crawler.client.model.PythonResult;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PythonCrawlerClient {

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
            PythonResult result = crawlerRestTemplate.postForObject(
                crawlerProperties.getBaseUrl() + "/internal/rank",
                request,
                PythonResult.class
            );
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler rank call failed");
            }
            return objectMapper.convertValue(result.getData(), new TypeReference<List<ExternalRankItem>>() {
            });
        } catch (Exception ex) {
            return buildFallbackRank(platform, category);
        }
    }

    public ExternalBookDetail fetchBook(String platform, String bookUrl) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("platform", platform);
            request.put("bookUrl", bookUrl);
            PythonResult result = crawlerRestTemplate.postForObject(
                crawlerProperties.getBaseUrl() + "/internal/book",
                request,
                PythonResult.class
            );
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler book call failed");
            }
            return objectMapper.convertValue(result.getData(), ExternalBookDetail.class);
        } catch (Exception ex) {
            return buildFallbackBook(bookUrl);
        }
    }

    public List<ExternalChapterItem> fetchChapters(String platform, String bookUrl, Integer chapterCount) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("platform", platform);
            request.put("bookUrl", bookUrl);
            request.put("chapterCount", chapterCount);
            PythonResult result = crawlerRestTemplate.postForObject(
                crawlerProperties.getBaseUrl() + "/internal/chapters",
                request,
                PythonResult.class
            );
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "crawler chapter call failed");
            }
            return objectMapper.convertValue(result.getData(), new TypeReference<List<ExternalChapterItem>>() {
            });
        } catch (Exception ex) {
            return buildFallbackChapters(chapterCount);
        }
    }

    private List<ExternalRankItem> buildFallbackRank(String platform, String category) {
        List<ExternalRankItem> fallback = new ArrayList<>();
        ExternalRankItem first = new ExternalRankItem();
        first.setRankNo(1);
        first.setBookName("示例热榜小说A");
        first.setAuthor("示例作者A");
        first.setIntro("本数据为本地兜底样例，用于联调。");
        first.setBookUrl("https://fanqienovel.com/page/demo-book-a");
        first.setPlatformBookId(platform + "-" + category + "-1");
        fallback.add(first);

        ExternalRankItem second = new ExternalRankItem();
        second.setRankNo(2);
        second.setBookName("示例热榜小说B");
        second.setAuthor("示例作者B");
        second.setIntro("本数据为本地兜底样例，用于联调。");
        second.setBookUrl("https://fanqienovel.com/page/demo-book-b");
        second.setPlatformBookId(platform + "-" + category + "-2");
        fallback.add(second);
        return fallback;
    }

    private ExternalBookDetail buildFallbackBook(String bookUrl) {
        ExternalBookDetail detail = new ExternalBookDetail();
        detail.setBookName("示例书籍详情");
        detail.setAuthor("示例作者");
        detail.setIntro("当前为本地兜底书籍详情，待Python爬虫联通后会被真实数据替换。");
        detail.setBookUrl(bookUrl);
        detail.setPlatformBookId("fallback-book-id");
        return detail;
    }

    private List<ExternalChapterItem> buildFallbackChapters(Integer chapterCount) {
        List<ExternalChapterItem> chapters = new ArrayList<>();
        int size = chapterCount == null ? 1 : chapterCount;
        for (int i = 1; i <= size; i++) {
            ExternalChapterItem item = new ExternalChapterItem();
            item.setChapterNo(i);
            item.setChapterTitle("第" + i + "章 示例章节");
            item.setContent("这是第" + i + "章的本地兜底内容，用于联调和流程验证。");
            chapters.add(item);
        }
        return chapters;
    }
}


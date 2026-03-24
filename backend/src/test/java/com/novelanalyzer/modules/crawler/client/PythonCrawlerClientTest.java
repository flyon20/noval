package com.novelanalyzer.modules.crawler.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.CrawlerProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonCrawlerClientTest {

    @Test
    void shouldSendInternalServiceTokenHeaderWhenFetchingRank() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CrawlerProperties properties = new CrawlerProperties();
        properties.setBaseUrl("http://crawler:5000");
        properties.setConnectTimeoutMillis(5000);
        properties.setReadTimeoutMillis(15000);
        properties.setInternalApiKey("crawler-internal-api-key-with-enough-length-1234567890");
        PythonCrawlerClient client = new PythonCrawlerClient(restTemplate, properties, new ObjectMapper());

        server.expect(requestTo("http://crawler:5000/internal/rank"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Service-Token", properties.getInternalApiKey()))
            .andRespond(withSuccess("""
                {
                  "code": 200,
                  "message": "success",
                  "data": [
                    {
                      "rankNo": 1,
                      "bookName": "Book A",
                      "author": "Author A",
                      "intro": "Intro A",
                      "bookUrl": "https://fanqienovel.com/page/101",
                      "platformBookId": "fanqie-male-hot-a-1"
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchRank("fanqie", "male-hot-a")).hasSize(1);
        server.verify();
    }

    @Test
    void shouldThrowWhenCrawlerReturnsClientErrorInsteadOfUsingFakeFallback() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CrawlerProperties properties = new CrawlerProperties();
        properties.setBaseUrl("http://crawler:5000");
        properties.setConnectTimeoutMillis(5000);
        properties.setReadTimeoutMillis(15000);
        properties.setInternalApiKey("crawler-internal-api-key-with-enough-length-1234567890");
        PythonCrawlerClient client = new PythonCrawlerClient(restTemplate, properties, new ObjectMapper());

        server.expect(requestTo("http://crawler:5000/internal/board-catalog?platform=fanqie"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Service-Token", properties.getInternalApiKey()))
            .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body("""
                {
                  "detail": "board catalog parse failed"
                }
                """));

        Assertions.assertThrows(RuntimeException.class, () -> client.fetchBoardCatalog("fanqie"));
        server.verify();
    }

    @Test
    void shouldFlattenGroupedBoardCatalogPayload() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CrawlerProperties properties = new CrawlerProperties();
        properties.setBaseUrl("http://crawler:5000");
        properties.setConnectTimeoutMillis(5000);
        properties.setReadTimeoutMillis(15000);
        properties.setInternalApiKey("crawler-internal-api-key-with-enough-length-1234567890");
        PythonCrawlerClient client = new PythonCrawlerClient(restTemplate, properties, new ObjectMapper());

        server.expect(requestTo("http://crawler:5000/internal/board-catalog?platform=fanqie"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {
                  "code": 200,
                  "message": "success",
                  "data": [
                    {
                      "channelCode": "male-new",
                      "channelName": "男频新书榜",
                      "boards": [
                        {
                          "boardCode": "262",
                          "boardName": "都市脑洞"
                        },
                        {
                          "boardCode": "1014",
                          "boardName": "都市高武"
                        }
                      ]
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchBoardCatalog("fanqie"))
            .extracting("channelCode", "boardCode", "boardName")
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("male-new", "262", "都市脑洞"),
                org.assertj.core.groups.Tuple.tuple("male-new", "1014", "都市高武")
            );
        server.verify();
    }

    @Test
    void shouldSendRuntimeCrawlerOptionsWhenFetchingChapters() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CrawlerProperties properties = new CrawlerProperties();
        properties.setBaseUrl("http://crawler:5000");
        properties.setConnectTimeoutMillis(5000);
        properties.setReadTimeoutMillis(15000);
        properties.setInternalApiKey("crawler-internal-api-key-with-enough-length-1234567890");
        PythonCrawlerClient client = new PythonCrawlerClient(restTemplate, properties, new ObjectMapper());

        server.expect(requestTo("http://crawler:5000/internal/chapters"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Service-Token", properties.getInternalApiKey()))
            .andExpect(content().json("""
                {
                  "platform": "fanqie",
                  "bookUrl": "https://fanqienovel.com/page/101",
                  "chapterCount": 3,
                  "startChapterNo": 6,
                  "timeoutSeconds": 25,
                  "chapterFetchWorkers": 4
                }
                """))
            .andRespond(withSuccess("""
                {
                  "code": 200,
                  "message": "success",
                  "data": [
                    {
                      "chapterNo": 1,
                      "chapterTitle": "Chapter 1",
                      "content": "Content 1"
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchChapters("fanqie", "https://fanqienovel.com/page/101", 3, 6, 25, 4)).hasSize(1);
        server.verify();
    }
}

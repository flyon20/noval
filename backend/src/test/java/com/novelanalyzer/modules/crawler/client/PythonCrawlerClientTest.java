package com.novelanalyzer.modules.crawler.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.config.CrawlerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
}

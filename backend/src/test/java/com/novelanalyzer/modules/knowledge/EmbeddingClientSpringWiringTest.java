package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.client.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:embeddingclientwiringdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.sql.init.mode=never",
        "app.auth.jwt-secret=test-jwt-secret-with-enough-length-1234567890",
        "app.crawler.internal-api-key=test-crawler-internal-api-key-1234567890",
        "app.ai.langgraph-worker.internal-api-key=test-langgraph-internal-key-1234567890",
        "app.knowledge.embedding.api-key=test-embedding-key"
    }
)
class EmbeddingClientSpringWiringTest {

    @Autowired
    private EmbeddingClient embeddingClient;

    @Test
    void shouldCreateEmbeddingClientBean() {
        assertThat(embeddingClient).isNotNull();
    }
}

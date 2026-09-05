package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResourcePolicyConfigTest {

    @Test
    void shouldBindJ3160ResourcePolicyDefaultsFromApplicationYaml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> propertySource : loader.load(
            "application",
            new FileSystemResource(findMainApplicationYaml())
        )) {
            environment.getPropertySources().addFirst(propertySource);
        }

        KnowledgeProperties properties = Binder.get(environment)
            .bind("app.knowledge", Bindable.of(KnowledgeProperties.class))
            .orElseThrow(IllegalStateException::new);
        KnowledgeProperties.ResourcePolicy policy = properties.getResourcePolicy();

        assertThat(policy.getMaxActiveDeepRuns()).isEqualTo(1);
        assertThat(policy.getMaxActiveFastRuns()).isEqualTo(2);
        assertThat(policy.getMaxActiveLlmCalls()).isEqualTo(2);
        assertThat(policy.getMaxDelegatedAgentConcurrency()).isEqualTo(1);
        assertThat(policy.getMaxIndexConcurrency()).isEqualTo(1);
        assertThat(policy.getMaxCrawlerConcurrency()).isEqualTo(2);
        assertThat(policy.getMemoryPausePercent()).isEqualTo(85);
        assertThat(policy.getMemoryRejectDeepPercent()).isEqualTo(92);
        assertThat(policy.getDiskWarnPercent()).isEqualTo(80);
        assertThat(policy.getDiskStopImportPercent()).isEqualTo(90);
        assertThat(policy.getQueueBacklogWarnCount()).isEqualTo(20);
        assertThat(policy.getQueueOldestWarnMinutes()).isEqualTo(5);
        assertThat(properties.getIndex().getWorkerConcurrency()).isEqualTo(1);
    }

    @Test
    void shouldApplySharedIndexConcurrencyToPolicyAndRabbitWorker() throws IOException {
        StandardEnvironment environment = loadApplicationEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
            "resourceOverride",
            Map.of("NOVAL_RESOURCE_MAX_INDEX_CONCURRENCY", "3")
        ));

        KnowledgeProperties properties = Binder.get(environment)
            .bind("app.knowledge", Bindable.of(KnowledgeProperties.class))
            .orElseThrow(IllegalStateException::new);

        assertThat(properties.getResourcePolicy().getMaxIndexConcurrency()).isEqualTo(3);
        assertThat(properties.getIndex().getWorkerConcurrency()).isEqualTo(3);
        assertThat(environment.getProperty("spring.rabbitmq.listener.simple.concurrency", Integer.class)).isEqualTo(3);
    }

    private StandardEnvironment loadApplicationEnvironment() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> propertySource : loader.load(
            "application",
            new FileSystemResource(findMainApplicationYaml())
        )) {
            environment.getPropertySources().addFirst(propertySource);
        }
        return environment;
    }

    private static Path findMainApplicationYaml() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("src/main/resources/application.yml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate src/main/resources/application.yml");
    }
}

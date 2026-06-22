package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeConfigTest {

    @Test
    void shouldReserveThreadsForQueueWorkersAndMaintenanceWhenQueueIsEnabled() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setQueueEnabled(true);
        properties.getIndex().setWorkerConcurrency(4);
        properties.getIndex().setMaxActiveJobs(1);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new KnowledgeConfig().knowledgeIndexTaskExecutor(properties);

        assertThat(executor.getMaxPoolSize()).isEqualTo(5);
        assertThat(executor.getCorePoolSize()).isEqualTo(5);
        executor.shutdown();
    }

    @Test
    void shouldBindKnowledgeIndexScheduleDefaultsFromApplicationYaml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        Path applicationYaml = findMainApplicationYaml();
        for (PropertySource<?> propertySource : loader.load("application", new FileSystemResource(applicationYaml))) {
            environment.getPropertySources().addFirst(propertySource);
        }

        KnowledgeProperties properties = Binder.get(environment)
            .bind("app.knowledge", Bindable.of(KnowledgeProperties.class))
            .orElseThrow(IllegalStateException::new);

        assertThat(properties.getIndex().isRankIncrementalEnabled()).isTrue();
        assertThat(properties.getIndex().getRankIncrementalCron()).isEqualTo("0 20 3 * * ?");
        assertThat(properties.getIndex().getRankIncrementalLimit()).isEqualTo(500);
        assertThat(properties.getIndex().isChapterMissingEnabled()).isFalse();
        assertThat(properties.getIndex().getChapterMissingCron()).isEqualTo("0 50 3 * * ?");
        assertThat(properties.getIndex().getChapterMissingLimit()).isEqualTo(100);
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

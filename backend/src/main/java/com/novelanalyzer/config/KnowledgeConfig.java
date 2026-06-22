package com.novelanalyzer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfig {

    @Bean(name = "knowledgeIndexTaskExecutor")
    public TaskExecutor knowledgeIndexTaskExecutor(KnowledgeProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int maxActiveJobs = Math.max(1, properties.getIndex().getMaxActiveJobs());
        int queueWorkers = properties.getIndex().isQueueEnabled()
            ? Math.max(1, properties.getIndex().getWorkerConcurrency())
            : 0;
        int poolSize = maxActiveJobs + queueWorkers;
        executor.setThreadNamePrefix("knowledge-index-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(16);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}

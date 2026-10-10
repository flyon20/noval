package com.novelanalyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class KnowledgeChatRunSchedulingConfig implements SchedulingConfigurer {

    public static final String CHAT_RUN_HEARTBEAT_TASK_SCHEDULER = "chatRunHeartbeatTaskScheduler";
    public static final String CHAT_RUN_DELTA_TASK_SCHEDULER = "chatRunDeltaTaskScheduler";
    public static final String CHAT_RUN_SSE_TASK_SCHEDULER = "chatRunSseTaskScheduler";
    public static final String CHAT_RUN_MAINTENANCE_TASK_SCHEDULER = "chatRunMaintenanceTaskScheduler";
    public static final String APPLICATION_SCHEDULED_EXECUTOR = "applicationScheduledExecutor";
    public static final String KNOWLEDGE_CHAT_FALLBACK_EXECUTOR = "knowledgeChatFallbackExecutor";

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatRunSchedulingConfig.class);

    @Bean(name = CHAT_RUN_HEARTBEAT_TASK_SCHEDULER, destroyMethod = "shutdown")
    @Primary
    public ThreadPoolTaskScheduler chatRunHeartbeatTaskScheduler() {
        return taskScheduler(1, "chat-run-heartbeat-");
    }

    @Bean(name = CHAT_RUN_DELTA_TASK_SCHEDULER, destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler chatRunDeltaTaskScheduler() {
        return taskScheduler(1, "chat-run-delta-");
    }

    @Bean(name = CHAT_RUN_SSE_TASK_SCHEDULER, destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler chatRunSseTaskScheduler() {
        return taskScheduler(2, "chat-run-sse-");
    }

    @Bean(name = CHAT_RUN_MAINTENANCE_TASK_SCHEDULER, destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler chatRunMaintenanceTaskScheduler() {
        return taskScheduler(2, "chat-run-maintenance-");
    }

    @Bean(name = APPLICATION_SCHEDULED_EXECUTOR, destroyMethod = "shutdown")
    public ScheduledExecutorService applicationScheduledExecutor() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("application-scheduled-");
        threadFactory.setDaemon(true);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            1,
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy()
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Bean(name = KNOWLEDGE_CHAT_FALLBACK_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolExecutor knowledgeChatFallbackExecutor() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("knowledge-chat-fallback-");
        threadFactory.setDaemon(true);
        return new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(applicationScheduledExecutor());
    }

    private ThreadPoolTaskScheduler taskScheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        scheduler.setErrorHandler(error -> LOGGER.error(
            "chat run scheduled task failed on {}",
            threadNamePrefix,
            error
        ));
        return scheduler;
    }
}

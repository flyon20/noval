package com.novelanalyzer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChatRunSchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(KnowledgeChatRunSchedulingConfig.class);

    @Test
    void shouldExposeBoundedIndependentChatRunSchedulers() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            ThreadPoolTaskScheduler heartbeat = context.getBean(
                KnowledgeChatRunSchedulingConfig.CHAT_RUN_HEARTBEAT_TASK_SCHEDULER,
                ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler sse = context.getBean(
                KnowledgeChatRunSchedulingConfig.CHAT_RUN_SSE_TASK_SCHEDULER,
                ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler delta = context.getBean(
                KnowledgeChatRunSchedulingConfig.CHAT_RUN_DELTA_TASK_SCHEDULER,
                ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler maintenance = context.getBean(
                KnowledgeChatRunSchedulingConfig.CHAT_RUN_MAINTENANCE_TASK_SCHEDULER,
                ThreadPoolTaskScheduler.class
            );

            assertThat(heartbeat).isNotSameAs(sse);
            assertThat(heartbeat).isNotSameAs(delta);
            assertThat(delta).isNotSameAs(sse);
            assertThat(maintenance).isNotSameAs(heartbeat);
            assertThat(maintenance).isNotSameAs(delta);
            assertThat(heartbeat.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(delta.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(maintenance.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(sse.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(heartbeat.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(delta.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(maintenance.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(sse.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(heartbeat.getThreadNamePrefix()).isEqualTo("chat-run-heartbeat-");
            assertThat(delta.getThreadNamePrefix()).isEqualTo("chat-run-delta-");
            assertThat(maintenance.getThreadNamePrefix()).isEqualTo("chat-run-maintenance-");
            assertThat(sse.getThreadNamePrefix()).isEqualTo("chat-run-sse-");

            // Existing type-only injection remains startable until the Run/SSE services add qualifiers.
            assertThat(context.getBean(TaskScheduler.class)).isSameAs(heartbeat);
        });
    }

    @Test
    void shouldKeepApplicationScheduledWorkOffChatRunSchedulers() {
        contextRunner.run(context -> {
            KnowledgeChatRunSchedulingConfig config = context.getBean(KnowledgeChatRunSchedulingConfig.class);
            ScheduledExecutorService applicationExecutor = context.getBean(
                KnowledgeChatRunSchedulingConfig.APPLICATION_SCHEDULED_EXECUTOR,
                ScheduledExecutorService.class
            );
            ScheduledThreadPoolExecutor boundedExecutor = (ScheduledThreadPoolExecutor) applicationExecutor;
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

            config.configureTasks(registrar);

            assertThat(boundedExecutor.getCorePoolSize()).isEqualTo(1);
            assertThat(boundedExecutor.getRemoveOnCancelPolicy()).isTrue();
            assertThat(registrar.getScheduler()).isNotNull();
            assertThat(registrar.getScheduler()).isNotSameAs(
                context.getBean(KnowledgeChatRunSchedulingConfig.CHAT_RUN_HEARTBEAT_TASK_SCHEDULER)
            );
            assertThat(registrar.getScheduler()).isNotSameAs(
                context.getBean(KnowledgeChatRunSchedulingConfig.CHAT_RUN_SSE_TASK_SCHEDULER)
            );
            assertThat(registrar.getScheduler()).isNotSameAs(
                context.getBean(KnowledgeChatRunSchedulingConfig.CHAT_RUN_DELTA_TASK_SCHEDULER)
            );
            assertThat(registrar.getScheduler()).isNotSameAs(
                context.getBean(KnowledgeChatRunSchedulingConfig.CHAT_RUN_MAINTENANCE_TASK_SCHEDULER)
            );
        });
    }

    @Test
    void shouldExposeSmallDaemonFallbackExecutorWithAbortPolicy() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            ThreadPoolExecutor fallback = context.getBean(
                KnowledgeChatRunSchedulingConfig.KNOWLEDGE_CHAT_FALLBACK_EXECUTOR,
                ThreadPoolExecutor.class
            );

            assertThat(fallback.getCorePoolSize()).isEqualTo(1);
            assertThat(fallback.getMaximumPoolSize()).isEqualTo(1);
            assertThat(fallback.getQueue().remainingCapacity()).isEqualTo(2);
            assertThat(fallback.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

            java.util.concurrent.atomic.AtomicReference<Thread> worker =
                new java.util.concurrent.atomic.AtomicReference<>();
            fallback.execute(() -> worker.set(Thread.currentThread()));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (worker.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(worker.get()).isNotNull();
            assertThat(worker.get().isDaemon()).isTrue();
            assertThat(worker.get().getName()).startsWith("knowledge-chat-fallback-");
        });
    }
}

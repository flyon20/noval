package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunRabbitConsumer;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeChatRunRabbitConsumerTest {

    @Test
    void shouldExecuteRunAndAckMessage() throws IOException {
        KnowledgeChatRunRabbitConsumer.ExecutionPort executionPort = mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class);
        KnowledgeChatRunRabbitConsumer consumer = new KnowledgeChatRunRabbitConsumer(executionPort);
        Channel channel = mock(Channel.class);

        consumer.consume(message("run-1", "EXECUTE"), channel, 41L, false);

        verify(executionPort).execute("run-1");
        verify(channel).basicAck(41L, false);
    }

    @Test
    void shouldCancelRunAndAckMessage() throws IOException {
        KnowledgeChatRunRabbitConsumer.ExecutionPort executionPort = mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class);
        KnowledgeChatRunRabbitConsumer consumer = new KnowledgeChatRunRabbitConsumer(executionPort);
        Channel channel = mock(Channel.class);

        consumer.consume(message("run-2", "cancel"), channel, 42L, false);

        verify(executionPort).cancel("run-2");
        verify(channel).basicAck(42L, false);
    }

    @Test
    void shouldRejectUnsupportedMessageToDeadLetterExchange() throws IOException {
        KnowledgeChatRunRabbitConsumer.ExecutionPort executionPort = mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class);
        KnowledgeChatRunRabbitConsumer consumer = new KnowledgeChatRunRabbitConsumer(executionPort);
        Channel channel = mock(Channel.class);

        consumer.consume(message("run-3", "RETRY"), channel, 43L, false);

        verifyNoInteractions(executionPort);
        verify(channel).basicReject(43L, false);
    }

    @Test
    void shouldRequeueFirstTransientExecutionFailure() throws IOException {
        KnowledgeChatRunRabbitConsumer.ExecutionPort executionPort = mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class);
        KnowledgeChatRunRabbitConsumer consumer = new KnowledgeChatRunRabbitConsumer(executionPort);
        Channel channel = mock(Channel.class);
        doThrow(new IllegalStateException("boom")).when(executionPort).execute("run-4");

        consumer.consume(message("run-4", "EXECUTE"), channel, 44L, false);

        verify(channel).basicNack(44L, false, true);
    }

    @Test
    void shouldDeadLetterRepeatedExecutionFailure() throws IOException {
        KnowledgeChatRunRabbitConsumer.ExecutionPort executionPort = mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class);
        KnowledgeChatRunRabbitConsumer consumer = new KnowledgeChatRunRabbitConsumer(executionPort);
        Channel channel = mock(Channel.class);
        doThrow(new IllegalStateException("boom")).when(executionPort).execute("run-5");

        consumer.consume(message("run-5", "EXECUTE"), channel, 45L, true);

        verify(channel).basicNack(45L, false, false);
    }

    @Test
    void shouldNotCreateConsumerWhenQueueIsDisabled() {
        new ApplicationContextRunner()
            .withBean(KnowledgeChatRunRabbitConsumer.ExecutionPort.class,
                () -> mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class))
            .withUserConfiguration(ConsumerConfiguration.class)
            .withPropertyValues("app.knowledge.chat-run.queue-enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(KnowledgeChatRunRabbitConsumer.class));
    }

    @Test
    void shouldSelectKnowledgeChatRunServiceWhenMultipleExecutionPortsExist() {
        new ApplicationContextRunner()
            .withBean(
                "knowledgeChatRunService",
                KnowledgeChatRunRabbitConsumer.ExecutionPort.class,
                () -> mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class)
            )
            .withBean(
                "knowledgeChatRunRabbitConsumer.ExecutionPort",
                KnowledgeChatRunRabbitConsumer.ExecutionPort.class,
                () -> mock(KnowledgeChatRunRabbitConsumer.ExecutionPort.class)
            )
            .withUserConfiguration(ConsumerConfiguration.class)
            .withPropertyValues("app.knowledge.chat-run.queue-enabled=true")
            .run(context -> assertThat(context).hasSingleBean(KnowledgeChatRunRabbitConsumer.class));
    }

    private KnowledgeChatRunQueueService.ChatRunQueueMessage message(String runId, String action) {
        return new KnowledgeChatRunQueueService.ChatRunQueueMessage(runId, action);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KnowledgeChatRunRabbitConsumer.class)
    static class ConsumerConfiguration {
    }
}

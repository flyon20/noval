package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectIngestRabbitConsumer;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeProjectIngestRabbitConsumerTest {

    @Test
    void shouldForwardMessageAttemptAndAckPersistedOutcome() throws Exception {
        KnowledgeProjectIngestRabbitConsumer.ExecutionPort executionPort =
            mock(KnowledgeProjectIngestRabbitConsumer.ExecutionPort.class);
        Channel channel = mock(Channel.class);
        KnowledgeProjectIngestRabbitConsumer consumer = new KnowledgeProjectIngestRabbitConsumer(executionPort);

        consumer.onMessage(
            new KnowledgeProjectIngestQueueService.ProjectIngestQueueMessage(17L, "EXECUTE", 3),
            message(41L),
            channel);

        verify(executionPort).execute(17L, 3);
        verify(channel).basicAck(41L, false);
    }

    @Test
    void shouldNackDatabaseFailureAndAckAfterRecovery() throws Exception {
        KnowledgeProjectIngestRabbitConsumer.ExecutionPort executionPort =
            mock(KnowledgeProjectIngestRabbitConsumer.ExecutionPort.class);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
            .when(executionPort).execute(17L, 2);
        Channel channel = mock(Channel.class);
        KnowledgeProjectIngestRabbitConsumer consumer = new KnowledgeProjectIngestRabbitConsumer(executionPort);

        consumer.onMessage(
            new KnowledgeProjectIngestQueueService.ProjectIngestQueueMessage(17L, "EXECUTE", 2),
            message(42L),
            channel);
        consumer.onMessage(
            new KnowledgeProjectIngestQueueService.ProjectIngestQueueMessage(17L, "EXECUTE", 2),
            message(44L),
            channel);

        verify(channel).basicNack(42L, false, true);
        verify(channel).basicAck(44L, false);
        verify(executionPort, times(2)).execute(17L, 2);
    }

    @Test
    void shouldDeadLetterInvalidPayload() throws Exception {
        KnowledgeProjectIngestRabbitConsumer.ExecutionPort executionPort =
            mock(KnowledgeProjectIngestRabbitConsumer.ExecutionPort.class);
        Channel channel = mock(Channel.class);
        KnowledgeProjectIngestRabbitConsumer consumer = new KnowledgeProjectIngestRabbitConsumer(executionPort);

        consumer.onMessage(
            new KnowledgeProjectIngestQueueService.ProjectIngestQueueMessage(17L, "UNKNOWN", 1),
            message(43L),
            channel);

        verifyNoInteractions(executionPort);
        verify(channel).basicNack(43L, false, false);
    }

    @Test
    void shouldSelectProjectIngestJobExecutorWhenMultipleExecutionPortsExist() {
        new ApplicationContextRunner()
            .withBean(
                "knowledgeProjectIngestJobExecutor",
                KnowledgeProjectIngestRabbitConsumer.ExecutionPort.class,
                () -> mock(KnowledgeProjectIngestRabbitConsumer.ExecutionPort.class)
            )
            .withBean(
                "knowledgeProjectIngestRabbitConsumer.ExecutionPort",
                KnowledgeProjectIngestRabbitConsumer.ExecutionPort.class,
                () -> mock(KnowledgeProjectIngestRabbitConsumer.ExecutionPort.class)
            )
            .withUserConfiguration(ConsumerConfiguration.class)
            .withPropertyValues("app.knowledge.project-ingest.queue-enabled=true")
            .run(context -> assertThat(context).hasSingleBean(KnowledgeProjectIngestRabbitConsumer.class));
    }

    private Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(new byte[0], properties);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(KnowledgeProjectIngestRabbitConsumer.class)
    static class ConsumerConfiguration {
    }
}

package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexQueueService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexQueueServiceTest {

    @Test
    void shouldPublishNewIndexJobToRabbitWorkQueue() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmPublishes(rabbitTemplate, true);
        KnowledgeIndexQueueService service = newService(rabbitTemplate, mock(RabbitAdmin.class), new KnowledgeProperties());
        KnowledgeIndexQueueService.IndexQueueItem item = item(1L, 0);

        boolean published = service.enqueue(item);

        assertThat(published).isTrue();
        ArgumentCaptor<KnowledgeIndexQueueService.IndexQueueItem> captor =
            ArgumentCaptor.forClass(KnowledgeIndexQueueService.IndexQueueItem.class);
        verify(rabbitTemplate).convertAndSend(
            eq("noval.knowledge.index"),
            eq("knowledge.index.book"),
            captor.capture(),
            any(CorrelationData.class)
        );
        assertThat(captor.getValue().jobId()).isEqualTo(1L);
        assertThat(captor.getValue().attempt()).isZero();
        assertThat(captor.getValue().rawPayload()).isNull();
    }

    @Test
    void shouldPublishRetryJobToDelayQueueWithNextAttempt() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmPublishes(rabbitTemplate, true);
        KnowledgeIndexQueueService service = newService(rabbitTemplate, mock(RabbitAdmin.class), new KnowledgeProperties());
        KnowledgeIndexQueueService.IndexQueueItem item = item(2L, 0).withRawPayload("{\"jobId\":2}");

        boolean published = service.retry(item, 2, 120);

        assertThat(published).isTrue();
        ArgumentCaptor<KnowledgeIndexQueueService.IndexQueueItem> captor =
            ArgumentCaptor.forClass(KnowledgeIndexQueueService.IndexQueueItem.class);
        verify(rabbitTemplate).convertAndSend(
            eq("noval.knowledge.index.retry"),
            eq("knowledge.index.book.retry.120s"),
            captor.capture(),
            any(CorrelationData.class)
        );
        assertThat(captor.getValue().jobId()).isEqualTo(2L);
        assertThat(captor.getValue().attempt()).isEqualTo(2);
        assertThat(captor.getValue().rawPayload()).isNull();
    }

    @Test
    void shouldTreatPublisherNackAsFailure() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmPublishes(rabbitTemplate, false);
        KnowledgeIndexQueueService service = newService(rabbitTemplate, mock(RabbitAdmin.class), new KnowledgeProperties());

        assertThat(service.enqueue(item(3L, 0))).isFalse();
    }

    @Test
    void shouldTreatMandatoryPublisherReturnAsFailureEvenWhenBrokerConfirms() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                "noval.knowledge.index",
                "knowledge.index.book"
            ));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            any(String.class),
            any(String.class),
            any(KnowledgeIndexQueueService.IndexQueueItem.class),
            any(CorrelationData.class)
        );
        KnowledgeIndexQueueService service = newService(rabbitTemplate, mock(RabbitAdmin.class), new KnowledgeProperties());

        assertThat(service.enqueue(item(4L, 0))).isFalse();
    }

    @Test
    void shouldResolveRetryBackoffFromConfiguredList() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getIndex().setRetryBackoffSeconds("10, 20, 40");
        KnowledgeIndexQueueService service = newService(mock(RabbitTemplate.class), mock(RabbitAdmin.class), properties);

        assertThat(service.retryBackoffSeconds(1)).isEqualTo(10L);
        assertThat(service.retryBackoffSeconds(2)).isEqualTo(20L);
        assertThat(service.retryBackoffSeconds(9)).isEqualTo(40L);
    }

    @Test
    void shouldReportRabbitQueueStatsWithoutUsingRedisKeys() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        KnowledgeIndexQueueService service = newService(mock(RabbitTemplate.class), rabbitAdmin, new KnowledgeProperties());
        when(rabbitAdmin.getQueueProperties("noval.knowledge.index.book")).thenReturn(propertiesWithMessageCount(5));
        when(rabbitAdmin.getQueueProperties("noval.knowledge.index.book.retry.30s")).thenReturn(propertiesWithMessageCount(2));
        when(rabbitAdmin.getQueueProperties("noval.knowledge.index.book.retry.120s")).thenReturn(propertiesWithMessageCount(3));
        when(rabbitAdmin.getQueueProperties("noval.knowledge.index.book.retry.600s")).thenReturn(propertiesWithMessageCount(0));

        KnowledgeIndexQueueService.QueueStats stats = service.stats();

        assertThat(stats.waiting()).isEqualTo(5);
        assertThat(stats.processing()).isZero();
        assertThat(stats.retry()).isEqualTo(5);
    }

    private KnowledgeIndexQueueService newService(RabbitTemplate rabbitTemplate,
                                                  RabbitAdmin rabbitAdmin,
                                                  KnowledgeProperties properties) {
        return new KnowledgeIndexQueueService(rabbitTemplate, rabbitAdmin, properties);
    }

    private void confirmPublishes(RabbitTemplate rabbitTemplate, boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "broker-nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            any(String.class),
            any(String.class),
            any(KnowledgeIndexQueueService.IndexQueueItem.class),
            any(CorrelationData.class)
        );
    }

    private Properties propertiesWithMessageCount(long count) {
        Properties properties = new Properties();
        properties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, count);
        return properties;
    }

    private KnowledgeIndexQueueService.IndexQueueItem item(Long jobId, int attempt) {
        return new KnowledgeIndexQueueService.IndexQueueItem(
            jobId,
            "KNOWLEDGE_INDEX_BOOK",
            "book:" + jobId,
            "book:" + jobId,
            "{\"bookId\":" + jobId + "}",
            jobId,
            7L,
            "ALL",
            "lock",
            "value",
            attempt,
            null
        );
    }
}

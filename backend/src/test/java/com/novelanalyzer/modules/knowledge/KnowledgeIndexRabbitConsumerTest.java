package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexJobExecutor;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexQueueService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeIndexRabbitConsumer;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexRabbitConsumerTest {

    @Test
    void shouldAckMessageWhenExecutorAcceptsQueuedJob() throws IOException {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeIndexRabbitConsumer consumer = new KnowledgeIndexRabbitConsumer(executor);
        Channel channel = mock(Channel.class);
        KnowledgeIndexQueueService.IndexQueueItem item = item(1L);
        when(executor.handleQueuedJob(item)).thenReturn(KnowledgeIndexJobExecutor.QueueConsumeAction.ACK);

        consumer.consume(item, channel, 42L);

        verify(channel).basicAck(42L, false);
    }

    @Test
    void shouldNackMessageForRedeliveryWhenExecutorRequestsRequeue() throws IOException {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeIndexRabbitConsumer consumer = new KnowledgeIndexRabbitConsumer(executor);
        Channel channel = mock(Channel.class);
        KnowledgeIndexQueueService.IndexQueueItem item = item(2L);
        when(executor.handleQueuedJob(item)).thenReturn(KnowledgeIndexJobExecutor.QueueConsumeAction.REQUEUE);

        consumer.consume(item, channel, 43L);

        verify(channel).basicNack(43L, false, true);
    }

    @Test
    void shouldRejectMessageWithoutRequeueWhenExecutorRequestsDeadLetter() throws IOException {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeIndexRabbitConsumer consumer = new KnowledgeIndexRabbitConsumer(executor);
        Channel channel = mock(Channel.class);
        KnowledgeIndexQueueService.IndexQueueItem item = item(3L);
        when(executor.handleQueuedJob(item)).thenReturn(KnowledgeIndexJobExecutor.QueueConsumeAction.DEAD_LETTER);

        consumer.consume(item, channel, 44L);

        verify(channel).basicReject(44L, false);
    }

    @Test
    void shouldNackMessageWhenAckFails() throws IOException {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeIndexRabbitConsumer consumer = new KnowledgeIndexRabbitConsumer(executor);
        Channel channel = mock(Channel.class);
        KnowledgeIndexQueueService.IndexQueueItem item = item(4L);
        when(executor.handleQueuedJob(item)).thenReturn(KnowledgeIndexJobExecutor.QueueConsumeAction.ACK);
        doThrow(new IOException("ack failed")).when(channel).basicAck(45L, false);

        consumer.consume(item, channel, 45L);

        verify(channel).basicNack(45L, false, true);
    }

    @Test
    void shouldNackMessageWhenExecutorThrows() throws IOException {
        KnowledgeIndexJobExecutor executor = mock(KnowledgeIndexJobExecutor.class);
        KnowledgeIndexRabbitConsumer consumer = new KnowledgeIndexRabbitConsumer(executor);
        Channel channel = mock(Channel.class);
        KnowledgeIndexQueueService.IndexQueueItem item = item(5L);
        when(executor.handleQueuedJob(item)).thenThrow(new IllegalStateException("boom"));

        consumer.consume(item, channel, 46L);

        verify(channel).basicNack(46L, false, true);
    }

    private KnowledgeIndexQueueService.IndexQueueItem item(Long jobId) {
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
            0,
            null
        );
    }
}

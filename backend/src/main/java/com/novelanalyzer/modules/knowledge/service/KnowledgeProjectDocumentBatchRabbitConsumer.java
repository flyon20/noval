package com.novelanalyzer.modules.knowledge.service;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.knowledge.document-batch", name = "queue-enabled", havingValue = "true")
public class KnowledgeProjectDocumentBatchRabbitConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectDocumentBatchRabbitConsumer.class);

    private final ExecutionPort executionPort;

    public KnowledgeProjectDocumentBatchRabbitConsumer(
        @Qualifier("knowledgeProjectDocumentBatchExecutor") ExecutionPort executionPort
    ) {
        this.executionPort = executionPort;
    }

    @RabbitListener(
        queues = "${app.knowledge.document-batch.rabbit.queue:noval.knowledge.document-batch.parse}",
        containerFactory = "knowledgeProjectDocumentBatchRabbitListenerContainerFactory"
    )
    public void onMessage(KnowledgeProjectDocumentBatchQueueService.DocumentBatchMessage payload,
                          Message message,
                          Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long batchId = payload == null ? null : payload.batchId();
        int attempt = payload == null ? 0 : payload.attempt();
        if (batchId == null || attempt <= 0 || !"PARSE".equals(payload.action())) {
            LOGGER.warn("document batch consumer discarded invalid payload batchId={} attempt={}", batchId, attempt);
            channel.basicNack(tag, false, false);
            return;
        }
        try {
            executionPort.execute(batchId, attempt);
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            LOGGER.error("document batch consumer failed batchId={} reason={}", batchId, ex.getMessage(), ex);
            channel.basicNack(tag, false, false);
        }
    }

    public interface ExecutionPort {
        void execute(Long batchId, int attempt);
    }
}

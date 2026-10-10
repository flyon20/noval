package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.knowledge.project-ingest", name = "queue-enabled", havingValue = "true")
public class KnowledgeProjectIngestRabbitConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectIngestRabbitConsumer.class);

    private final ExecutionPort executionPort;

    public KnowledgeProjectIngestRabbitConsumer(
        @Qualifier("knowledgeProjectIngestJobExecutor") ExecutionPort executionPort
    ) {
        this.executionPort = executionPort;
    }

    @RabbitListener(queues = "${app.knowledge.project-ingest.rabbit.queue:noval.knowledge.project-ingest.job}",
        containerFactory = "knowledgeProjectIngestRabbitListenerContainerFactory")
    public void onMessage(KnowledgeProjectIngestQueueService.ProjectIngestQueueMessage payload,
                          Message message,
                          Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long jobId = payload == null ? null : payload.ingestJobId();
        int attempt = payload == null ? 0 : payload.attempt();
        if (jobId == null || attempt <= 0 || !"EXECUTE".equals(payload.action())) {
            LOGGER.warn("project ingest consumer discarded invalid payload jobId={} attempt={}", jobId, attempt);
            channel.basicNack(tag, false, false);
            return;
        }
        try {
            executionPort.execute(jobId, attempt);
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            LOGGER.error("project ingest consumer failed jobId={} reason={}", jobId, ex.getMessage(), ex);
            channel.basicNack(tag, false, true);
        }
    }

    public interface ExecutionPort {
        void execute(Long ingestJobId, int attempt);
    }
}

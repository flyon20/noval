package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class KnowledgeProjectIngestQueueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectIngestQueueService.class);
    private static final long PUBLISH_CONFIRM_TIMEOUT_MILLIS = 5_000L;

    private final RabbitTemplate rabbitTemplate;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeProjectIngestQueueService(ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                                              KnowledgeProperties knowledgeProperties) {
        this.rabbitTemplate = rabbitTemplateProvider == null ? null : rabbitTemplateProvider.getIfAvailable();
        this.knowledgeProperties = knowledgeProperties == null ? new KnowledgeProperties() : knowledgeProperties;
    }

    public boolean publishExecute(Long ingestJobId, int attempt) {
        if (ingestJobId == null || rabbitTemplate == null || !knowledgeProperties.getProjectIngest().isQueueEnabled()) {
            return false;
        }
        KnowledgeProperties.ProjectIngest.Rabbit rabbit = knowledgeProperties.getProjectIngest().getRabbit();
        ProjectIngestQueueMessage message = new ProjectIngestQueueMessage(ingestJobId, "EXECUTE", attempt);
        CorrelationData correlationData = new CorrelationData("project-ingest:" + ingestJobId + ":" + attempt + ":" + UUID.randomUUID());
        try {
            rabbitTemplate.convertAndSend(rabbit.getExchange(), rabbit.getRoutingKey(), message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(PUBLISH_CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                LOGGER.warn("project ingest publish was not confirmed jobId={} attempt={} reason={}",
                    ingestJobId, attempt, confirm.getReason());
                return false;
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                LOGGER.warn("project ingest publish was returned jobId={} attempt={} replyCode={} replyText={}",
                    ingestJobId, attempt, returned.getReplyCode(), returned.getReplyText());
                return false;
            }
            return true;
        } catch (Exception ex) {
            LOGGER.warn("project ingest publish failed jobId={} attempt={} reason={}", ingestJobId, attempt, ex.getMessage());
            return false;
        }
    }

    public long retryBackoffSeconds(int nextAttempt) {
        String configured = knowledgeProperties.getProjectIngest().getRetryBackoffSeconds();
        String[] values = configured == null ? new String[0] : configured.split(",");
        int index = Math.max(0, nextAttempt - 2);
        if (values.length == 0) {
            return 30L;
        }
        String selected = values[Math.min(index, values.length - 1)].trim();
        try {
            return Math.max(1L, Long.parseLong(selected));
        } catch (NumberFormatException ex) {
            return 30L;
        }
    }

    public record ProjectIngestQueueMessage(Long ingestJobId, String action, int attempt) {
    }
}

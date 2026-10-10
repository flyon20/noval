package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class KnowledgeProjectDocumentBatchQueueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeProjectDocumentBatchQueueService.class);
    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000L;

    private final RabbitTemplate rabbitTemplate;
    private final KnowledgeProperties properties;

    public KnowledgeProjectDocumentBatchQueueService(ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
                                                     KnowledgeProperties properties) {
        this.rabbitTemplate = rabbitTemplateProvider == null ? null : rabbitTemplateProvider.getIfAvailable();
        this.properties = properties == null ? new KnowledgeProperties() : properties;
    }

    public boolean publish(Long batchId, int attempt) {
        if (batchId == null || attempt <= 0 || rabbitTemplate == null
            || !properties.getDocumentBatch().isQueueEnabled()) {
            return false;
        }
        KnowledgeProperties.DocumentBatch.Rabbit rabbit = properties.getDocumentBatch().getRabbit();
        CorrelationData correlation = new CorrelationData(
            "project-document:" + batchId + ":" + attempt + ":" + UUID.randomUUID());
        try {
            rabbitTemplate.convertAndSend(
                rabbit.getExchange(),
                rabbit.getRoutingKey(),
                new DocumentBatchMessage(batchId, "PARSE", attempt),
                correlation
            );
            CorrelationData.Confirm confirm = correlation.getFuture()
                .get(CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlation.getReturned();
            if (!confirm.isAck() || returned != null) {
                LOGGER.warn("document batch publish rejected batchId={} attempt={} reason={}",
                    batchId, attempt, confirm.getReason());
                return false;
            }
            return true;
        } catch (Exception ex) {
            LOGGER.warn("document batch publish failed batchId={} attempt={} reason={}",
                batchId, attempt, ex.getMessage());
            return false;
        }
    }

    public record DocumentBatchMessage(Long batchId, String action, int attempt) {
    }
}

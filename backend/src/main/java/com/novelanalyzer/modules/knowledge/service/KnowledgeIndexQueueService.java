package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class KnowledgeIndexQueueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexQueueService.class);
    private static final String DEFAULT_EXCHANGE = "noval.knowledge.index";
    private static final String DEFAULT_QUEUE = "noval.knowledge.index.book";
    private static final String DEFAULT_ROUTING_KEY = "knowledge.index.book";
    private static final String DEFAULT_RETRY_EXCHANGE = "noval.knowledge.index.retry";
    private static final String DEFAULT_RETRY_ROUTING_KEY_PREFIX = "knowledge.index.book.retry";
    private static final long PUBLISH_CONFIRM_TIMEOUT_MILLIS = 5000L;

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin rabbitAdmin;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeIndexQueueService(RabbitTemplate rabbitTemplate,
                                      AmqpAdmin rabbitAdmin,
                                      KnowledgeProperties knowledgeProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitAdmin = rabbitAdmin;
        this.knowledgeProperties = knowledgeProperties;
    }

    public boolean enqueue(IndexQueueItem item) {
        return publish(
            exchange(),
            routingKey(),
            item.withAttempt(Math.max(0, item.attempt())).withoutRawPayload(),
            "enqueue"
        );
    }

    public boolean retry(IndexQueueItem item, int nextAttempt, long delaySeconds) {
        long safeDelaySeconds = Math.max(1, delaySeconds);
        return publish(
            retryExchange(),
            retryRoutingKey(safeDelaySeconds),
            item.withAttempt(nextAttempt).withoutRawPayload(),
            "retry"
        );
    }

    private boolean publish(String exchange,
                            String routingKey,
                            IndexQueueItem item,
                            String operation) {
        try {
            CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
            rabbitTemplate.convertAndSend(exchange, routingKey, item, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(PUBLISH_CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                LOGGER.warn("knowledge index rabbit {} was not confirmed: jobId={}, reason={}",
                    operation,
                    item.jobId(),
                    confirm.getReason());
                return false;
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                LOGGER.warn("knowledge index rabbit {} was returned: jobId={}, replyCode={}, replyText={}",
                    operation,
                    item.jobId(),
                    returned.getReplyCode(),
                    returned.getReplyText());
                return false;
            }
            return true;
        } catch (Exception ex) {
            LOGGER.warn("knowledge index rabbit {} failed: jobId={}, message={}",
                operation,
                item == null ? null : item.jobId(),
                ex.getMessage());
            return false;
        }
    }

    public QueueStats stats() {
        long waiting = queueMessageCount(queueName());
        long retry = retryDelays().stream()
            .mapToLong(delay -> queueMessageCount(retryQueueName(delay)))
            .sum();
        return new QueueStats(waiting, 0, retry);
    }

    public long retryBackoffSeconds(int attempt) {
        List<Long> values = parseBackoffSeconds();
        if (values.isEmpty()) {
            return 60;
        }
        int index = Math.max(0, Math.min(attempt - 1, values.size() - 1));
        return values.get(index);
    }

    public String queueName() {
        return valueOrDefault(rabbit().getQueue(), DEFAULT_QUEUE);
    }

    public String exchange() {
        return valueOrDefault(rabbit().getExchange(), DEFAULT_EXCHANGE);
    }

    public String routingKey() {
        return valueOrDefault(rabbit().getRoutingKey(), DEFAULT_ROUTING_KEY);
    }

    public String retryExchange() {
        return valueOrDefault(rabbit().getRetryExchange(), DEFAULT_RETRY_EXCHANGE);
    }

    public String retryRoutingKey(long delaySeconds) {
        return valueOrDefault(rabbit().getRetryRoutingKeyPrefix(), DEFAULT_RETRY_ROUTING_KEY_PREFIX)
            + "."
            + Math.max(1, delaySeconds)
            + "s";
    }

    public String retryQueueName(long delaySeconds) {
        return queueName() + ".retry." + Math.max(1, delaySeconds) + "s";
    }

    private KnowledgeProperties.Index.Rabbit rabbit() {
        KnowledgeProperties.Index index = knowledgeProperties.getIndex();
        return index == null ? new KnowledgeProperties.Index.Rabbit() : index.getRabbit();
    }

    private long queueMessageCount(String queueName) {
        try {
            Properties properties = rabbitAdmin.getQueueProperties(queueName);
            if (properties == null) {
                return 0L;
            }
            return longValue(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
        } catch (Exception ex) {
            LOGGER.debug("knowledge index rabbit queue stats unavailable: queue={}, message={}", queueName, ex.getMessage());
            return 0L;
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private List<Long> retryDelays() {
        List<Long> configured = parseBackoffSeconds();
        if (configured.isEmpty()) {
            configured = List.of(30L, 120L, 600L);
        }
        return new ArrayList<>(new LinkedHashSet<>(configured));
    }

    private List<Long> parseBackoffSeconds() {
        KnowledgeProperties.Index index = knowledgeProperties.getIndex();
        String configured = index == null ? null : index.getRetryBackoffSeconds();
        if (configured == null || configured.isBlank()) {
            return List.of(30L, 120L, 600L);
        }
        List<Long> values = new ArrayList<>();
        for (String item : configured.split(",")) {
            try {
                values.add(Math.max(1, Long.parseLong(item.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return values;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public record IndexQueueItem(
        Long jobId,
        String jobType,
        String jobKey,
        String resourceKey,
        String requestJson,
        Long bookId,
        Long triggerUserId,
        String mode,
        String lockKey,
        String lockValue,
        int attempt,
        String rawPayload
    ) {
        public IndexQueueItem withRawPayload(String payload) {
            return new IndexQueueItem(jobId, jobType, jobKey, resourceKey, requestJson, bookId, triggerUserId, mode, lockKey, lockValue, attempt, payload);
        }

        public IndexQueueItem withoutRawPayload() {
            return new IndexQueueItem(jobId, jobType, jobKey, resourceKey, requestJson, bookId, triggerUserId, mode, lockKey, lockValue, attempt, null);
        }

        public IndexQueueItem withAttempt(int nextAttempt) {
            return new IndexQueueItem(jobId, jobType, jobKey, resourceKey, requestJson, bookId, triggerUserId, mode, lockKey, lockValue, nextAttempt, rawPayload);
        }
    }

    public record QueueStats(long waiting, long processing, long retry) {
    }
}

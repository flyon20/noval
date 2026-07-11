package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

@Service
@ConditionalOnProperty(prefix = "app.knowledge.eval", name = "queue-enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeAgentEvalQueueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeAgentEvalQueueService.class);
    private static final String DEFAULT_EXCHANGE = "noval.knowledge.eval";
    private static final String DEFAULT_QUEUE = "noval.knowledge.eval.run";
    private static final String DEFAULT_ROUTING_KEY = "knowledge.eval.run";
    private static final String DEFAULT_RETRY_EXCHANGE = "noval.knowledge.eval.retry";
    private static final String DEFAULT_RETRY_ROUTING_KEY_PREFIX = "knowledge.eval.run.retry";

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin rabbitAdmin;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeAgentEvalQueueService(RabbitTemplate rabbitTemplate,
                                          AmqpAdmin rabbitAdmin,
                                          KnowledgeProperties knowledgeProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitAdmin = rabbitAdmin;
        this.knowledgeProperties = knowledgeProperties;
    }

    public boolean enqueue(EvalQueueItem item) {
        try {
            rabbitTemplate.convertAndSend(exchange(), routingKey(), item.withAttempt(item.attempt()).withoutRawPayload());
            return true;
        } catch (Exception ex) {
            LOGGER.warn("knowledge eval rabbit enqueue failed: {}", ex.getMessage());
            return false;
        }
    }

    public boolean retry(EvalQueueItem item, int nextAttempt, long delaySeconds) {
        try {
            long safeDelaySeconds = Math.max(1, delaySeconds);
            rabbitTemplate.convertAndSend(
                retryExchange(),
                retryRoutingKey(safeDelaySeconds),
                item.withAttempt(nextAttempt).withoutRawPayload()
            );
            return true;
        } catch (Exception ex) {
            LOGGER.warn("knowledge eval rabbit retry publish failed: {}", ex.getMessage());
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
        return valueOrDefault(evalRabbit().getQueue(), DEFAULT_QUEUE);
    }

    public String exchange() {
        return valueOrDefault(evalRabbit().getExchange(), DEFAULT_EXCHANGE);
    }

    public String routingKey() {
        return valueOrDefault(evalRabbit().getRoutingKey(), DEFAULT_ROUTING_KEY);
    }

    public String retryExchange() {
        return valueOrDefault(evalRabbit().getRetryExchange(), DEFAULT_RETRY_EXCHANGE);
    }

    public String retryRoutingKey(long delaySeconds) {
        return valueOrDefault(evalRabbit().getRetryRoutingKeyPrefix(), DEFAULT_RETRY_ROUTING_KEY_PREFIX)
            + "."
            + Math.max(1, delaySeconds)
            + "s";
    }

    public String retryQueueName(long delaySeconds) {
        return queueName() + ".retry." + Math.max(1, delaySeconds) + "s";
    }

    private KnowledgeProperties.Eval.Rabbit evalRabbit() {
        KnowledgeProperties.Eval eval = knowledgeProperties.getEval();
        return eval == null ? new KnowledgeProperties.Eval.Rabbit() : eval.getRabbit();
    }

    private long queueMessageCount(String queueName) {
        try {
            Properties properties = rabbitAdmin.getQueueProperties(queueName);
            if (properties == null) {
                return 0L;
            }
            Object value = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            LOGGER.debug("knowledge eval rabbit queue stats unavailable: queue={}, message={}", queueName, ex.getMessage());
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
        KnowledgeProperties.Eval eval = knowledgeProperties.getEval();
        String configured = eval == null ? null : eval.getRetryBackoffSeconds();
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

    public record EvalQueueItem(
        Long runId,
        String runKey,
        String suiteName,
        String runnerName,
        String evaluatorName,
        String modelName,
        Integer caseLimit,
        String cancelKey,
        String progressKey,
        int attempt,
        String rawPayload
    ) {
        public EvalQueueItem withRawPayload(String payload) {
            return new EvalQueueItem(runId, runKey, suiteName, runnerName, evaluatorName, modelName, caseLimit, cancelKey, progressKey, attempt, payload);
        }

        public EvalQueueItem withoutRawPayload() {
            return new EvalQueueItem(runId, runKey, suiteName, runnerName, evaluatorName, modelName, caseLimit, cancelKey, progressKey, attempt, null);
        }

        public EvalQueueItem withAttempt(int nextAttempt) {
            return new EvalQueueItem(runId, runKey, suiteName, runnerName, evaluatorName, modelName, caseLimit, cancelKey, progressKey, nextAttempt, rawPayload);
        }
    }

    public record QueueStats(long waiting, long processing, long retry) {
    }
}

package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "app.knowledge.chat-run", name = "queue-enabled", havingValue = "true")
public class KnowledgeChatRunQueueService {

    public static final String ACTION_EXECUTE = "EXECUTE";
    public static final String ACTION_CANCEL = "CANCEL";

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatRunQueueService.class);
    private static final long PUBLISH_CONFIRM_TIMEOUT_MILLIS = 5000L;

    private final RabbitTemplate rabbitTemplate;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeChatRunQueueService(RabbitTemplate rabbitTemplate,
                                        KnowledgeProperties knowledgeProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.knowledgeProperties = knowledgeProperties;
    }

    public boolean publishExecute(String runId) {
        return publish(runId, ACTION_EXECUTE);
    }

    public boolean publishCancel(String runId) {
        return publish(runId, ACTION_CANCEL);
    }

    public boolean publish(String runId, String action) {
        String normalizedRunId = trimToNull(runId);
        String normalizedAction = normalizeAction(action);
        if (normalizedRunId == null || normalizedAction == null) {
            return false;
        }
        try {
            KnowledgeProperties.ChatRun.Rabbit rabbit = rabbit();
            CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
            rabbitTemplate.convertAndSend(
                rabbit.getExchange(),
                rabbit.getRoutingKey(),
                new ChatRunQueueMessage(normalizedRunId, normalizedAction),
                correlationData
            );
            CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(PUBLISH_CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                LOGGER.warn("knowledge chat run rabbit publish was not confirmed: runId={}, action={}, reason={}",
                    normalizedRunId,
                    normalizedAction,
                    confirm.getReason());
                return false;
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                LOGGER.warn("knowledge chat run rabbit publish was returned: runId={}, action={}, replyCode={}, replyText={}",
                    normalizedRunId,
                    normalizedAction,
                    returned.getReplyCode(),
                    returned.getReplyText());
                return false;
            }
            return true;
        } catch (Exception ex) {
            LOGGER.warn("knowledge chat run rabbit publish failed: runId={}, action={}, message={}",
                normalizedRunId,
                normalizedAction,
                ex.getMessage());
            return false;
        }
    }

    private KnowledgeProperties.ChatRun.Rabbit rabbit() {
        KnowledgeProperties.ChatRun chatRun = knowledgeProperties.getChatRun();
        return chatRun == null ? new KnowledgeProperties.ChatRun.Rabbit() : chatRun.getRabbit();
    }

    private String normalizeAction(String action) {
        String normalized = trimToNull(action);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return ACTION_EXECUTE.equals(normalized) || ACTION_CANCEL.equals(normalized) ? normalized : null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ChatRunQueueMessage(String runId, String action) {
    }
}

package com.novelanalyzer.modules.knowledge.service;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;

@Service
@ConditionalOnProperty(prefix = "app.knowledge.chat-run", name = "queue-enabled", havingValue = "true")
public class KnowledgeChatRunRabbitConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeChatRunRabbitConsumer.class);

    private final ExecutionPort executionPort;

    public KnowledgeChatRunRabbitConsumer(
        @Qualifier("knowledgeChatRunService") ExecutionPort executionPort
    ) {
        this.executionPort = executionPort;
    }

    @RabbitListener(queues = "${app.knowledge.chat-run.rabbit.queue:noval.knowledge.chat.run}",
        containerFactory = "knowledgeChatRunRabbitListenerContainerFactory")
    public void consume(KnowledgeChatRunQueueService.ChatRunQueueMessage message,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                        @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) throws IOException {
        String runId = message == null ? null : trimToNull(message.runId());
        String action = message == null ? null : normalizeAction(message.action());
        if (runId == null || action == null) {
            channel.basicReject(deliveryTag, false);
            return;
        }

        try {
            switch (action) {
                case KnowledgeChatRunQueueService.ACTION_EXECUTE -> executionPort.execute(runId);
                case KnowledgeChatRunQueueService.ACTION_CANCEL -> executionPort.cancel(runId);
                default -> throw new IllegalArgumentException("unsupported chat run action");
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            LOGGER.warn("knowledge chat run rabbit consume failed: runId={}, action={}, message={}",
                runId,
                action,
                ex.getMessage());
            channel.basicNack(deliveryTag, false, !Boolean.TRUE.equals(redelivered));
        }
    }

    private String normalizeAction(String action) {
        String normalized = trimToNull(action);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return KnowledgeChatRunQueueService.ACTION_EXECUTE.equals(normalized)
            || KnowledgeChatRunQueueService.ACTION_CANCEL.equals(normalized)
            ? normalized
            : null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public interface ExecutionPort {
        void execute(String runId);

        void cancel(String runId);
    }
}

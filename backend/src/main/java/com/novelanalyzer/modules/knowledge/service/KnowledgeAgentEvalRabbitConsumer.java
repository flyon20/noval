package com.novelanalyzer.modules.knowledge.service;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@ConditionalOnProperty(prefix = "app.knowledge.eval", name = "queue-enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeAgentEvalRabbitConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeAgentEvalRabbitConsumer.class);

    private final KnowledgeAgentEvalJobExecutor executor;

    public KnowledgeAgentEvalRabbitConsumer(KnowledgeAgentEvalJobExecutor executor) {
        this.executor = executor;
    }

    @RabbitListener(queues = "${app.knowledge.eval.rabbit.queue:noval.knowledge.eval.run}",
        containerFactory = "knowledgeEvalRabbitListenerContainerFactory")
    public void consume(KnowledgeAgentEvalQueueService.EvalQueueItem item,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            KnowledgeAgentEvalJobExecutor.QueueConsumeAction action = executor.handleQueuedJob(item);
            switch (action) {
                case ACK -> channel.basicAck(deliveryTag, false);
                case REQUEUE -> channel.basicNack(deliveryTag, false, true);
                case DEAD_LETTER -> channel.basicReject(deliveryTag, false);
            }
        } catch (Exception ex) {
            LOGGER.warn("knowledge eval rabbit ack failed: deliveryTag={}, message={}", deliveryTag, ex.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }
}

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
@ConditionalOnProperty(prefix = "app.knowledge.index", name = "queue-enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexRabbitConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexRabbitConsumer.class);

    private final KnowledgeIndexJobExecutor knowledgeIndexJobExecutor;

    public KnowledgeIndexRabbitConsumer(KnowledgeIndexJobExecutor knowledgeIndexJobExecutor) {
        this.knowledgeIndexJobExecutor = knowledgeIndexJobExecutor;
    }

    @RabbitListener(queues = "${app.knowledge.index.rabbit.queue:noval.knowledge.index.book}",
        containerFactory = "knowledgeIndexRabbitListenerContainerFactory")
    public void consume(KnowledgeIndexQueueService.IndexQueueItem item,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            KnowledgeIndexJobExecutor.QueueConsumeAction action = knowledgeIndexJobExecutor.handleQueuedJob(item);
            switch (action) {
                case ACK -> channel.basicAck(deliveryTag, false);
                case REQUEUE -> channel.basicNack(deliveryTag, false, true);
                case DEAD_LETTER -> channel.basicReject(deliveryTag, false);
            }
        } catch (Exception ex) {
            LOGGER.warn("knowledge index rabbit ack failed: deliveryTag={}, action={}, message={}",
                deliveryTag,
                "unknown",
                ex.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }
}

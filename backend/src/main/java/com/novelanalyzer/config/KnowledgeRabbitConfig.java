package com.novelanalyzer.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableRabbit
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeRabbitConfig {

    @Bean
    public Jackson2JsonMessageConverter knowledgeIndexMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplateCustomizer knowledgeIndexRabbitTemplateCustomizer(Jackson2JsonMessageConverter messageConverter) {
        return rabbitTemplate -> {
            rabbitTemplate.setMessageConverter(messageConverter);
            rabbitTemplate.setMandatory(true);
        };
    }

    @Bean
    public SimpleRabbitListenerContainerFactory knowledgeIndexRabbitListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                             Jackson2JsonMessageConverter messageConverter,
                                                                                             KnowledgeProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        int concurrency = Math.max(1, properties.getIndex().getWorkerConcurrency());
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(concurrency);
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory knowledgeEvalRabbitListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                                            Jackson2JsonMessageConverter messageConverter,
                                                                                            KnowledgeProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        int concurrency = Math.max(1, properties.getEval().getWorkerConcurrency());
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(concurrency);
        return factory;
    }

    @Bean
    public Declarables knowledgeIndexRabbitDeclarables(KnowledgeProperties properties) {
        KnowledgeProperties.Index.Rabbit rabbit = rabbit(properties);
        DirectExchange mainExchange = new DirectExchange(rabbit.getExchange(), true, false);
        DirectExchange retryExchange = new DirectExchange(rabbit.getRetryExchange(), true, false);
        DirectExchange deadLetterExchange = new DirectExchange(rabbit.getDeadLetterExchange(), true, false);
        Queue mainQueue = QueueBuilder.durable(rabbit.getQueue())
            .deadLetterExchange(deadLetterExchange.getName())
            .deadLetterRoutingKey(rabbit.getDeadLetterRoutingKey())
            .build();
        Queue deadLetterQueue = QueueBuilder.durable(rabbit.getDeadLetterQueue()).build();

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(mainExchange);
        declarables.add(retryExchange);
        declarables.add(deadLetterExchange);
        declarables.add(mainQueue);
        declarables.add(deadLetterQueue);
        declarables.add(BindingBuilder.bind(mainQueue).to(mainExchange).with(rabbit.getRoutingKey()));
        declarables.add(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(rabbit.getDeadLetterRoutingKey()));
        for (Long delaySeconds : retryDelays(properties)) {
            Queue retryQueue = QueueBuilder.durable(rabbit.getQueue() + ".retry." + delaySeconds + "s")
                .withArgument("x-message-ttl", Math.toIntExact(Math.max(1L, delaySeconds) * 1000L))
                .withArgument("x-dead-letter-exchange", mainExchange.getName())
                .withArgument("x-dead-letter-routing-key", rabbit.getRoutingKey())
                .build();
            declarables.add(retryQueue);
            declarables.add(BindingBuilder.bind(retryQueue).to(retryExchange).with(rabbit.getRetryRoutingKeyPrefix() + "." + delaySeconds + "s"));
        }
        return new Declarables(declarables);
    }

    @Bean
    public Declarables knowledgeEvalRabbitDeclarables(KnowledgeProperties properties) {
        KnowledgeProperties.Eval.Rabbit rabbit = evalRabbit(properties);
        DirectExchange mainExchange = new DirectExchange(rabbit.getExchange(), true, false);
        DirectExchange retryExchange = new DirectExchange(rabbit.getRetryExchange(), true, false);
        DirectExchange deadLetterExchange = new DirectExchange(rabbit.getDeadLetterExchange(), true, false);
        Queue mainQueue = QueueBuilder.durable(rabbit.getQueue())
            .deadLetterExchange(deadLetterExchange.getName())
            .deadLetterRoutingKey(rabbit.getDeadLetterRoutingKey())
            .build();
        Queue deadLetterQueue = QueueBuilder.durable(rabbit.getDeadLetterQueue()).build();

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(mainExchange);
        declarables.add(retryExchange);
        declarables.add(deadLetterExchange);
        declarables.add(mainQueue);
        declarables.add(deadLetterQueue);
        declarables.add(BindingBuilder.bind(mainQueue).to(mainExchange).with(rabbit.getRoutingKey()));
        declarables.add(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(rabbit.getDeadLetterRoutingKey()));
        for (Long delaySeconds : evalRetryDelays(properties)) {
            Queue retryQueue = QueueBuilder.durable(rabbit.getQueue() + ".retry." + delaySeconds + "s")
                .withArgument("x-message-ttl", Math.toIntExact(Math.max(1L, delaySeconds) * 1000L))
                .withArgument("x-dead-letter-exchange", mainExchange.getName())
                .withArgument("x-dead-letter-routing-key", rabbit.getRoutingKey())
                .build();
            declarables.add(retryQueue);
            declarables.add(BindingBuilder.bind(retryQueue).to(retryExchange).with(rabbit.getRetryRoutingKeyPrefix() + "." + delaySeconds + "s"));
        }
        return new Declarables(declarables);
    }

    private KnowledgeProperties.Index.Rabbit rabbit(KnowledgeProperties properties) {
        KnowledgeProperties.Index index = properties.getIndex();
        return index == null ? new KnowledgeProperties.Index.Rabbit() : index.getRabbit();
    }

    private KnowledgeProperties.Eval.Rabbit evalRabbit(KnowledgeProperties properties) {
        KnowledgeProperties.Eval eval = properties.getEval();
        return eval == null ? new KnowledgeProperties.Eval.Rabbit() : eval.getRabbit();
    }

    private List<Long> retryDelays(KnowledgeProperties properties) {
        KnowledgeProperties.Index index = properties.getIndex();
        String configured = index == null ? null : index.getRetryBackoffSeconds();
        if (configured == null || configured.isBlank()) {
            return List.of(30L, 120L, 600L);
        }
        List<Long> values = new ArrayList<>();
        for (String item : configured.split(",")) {
            try {
                values.add(Math.max(1L, Long.parseLong(item.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return values.isEmpty() ? List.of(30L, 120L, 600L) : values;
    }

    private List<Long> evalRetryDelays(KnowledgeProperties properties) {
        KnowledgeProperties.Eval eval = properties.getEval();
        String configured = eval == null ? null : eval.getRetryBackoffSeconds();
        if (configured == null || configured.isBlank()) {
            return List.of(30L, 120L, 600L);
        }
        List<Long> values = new ArrayList<>();
        for (String item : configured.split(",")) {
            try {
                values.add(Math.max(1L, Long.parseLong(item.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return values.isEmpty() ? List.of(30L, 120L, 600L) : values;
    }
}

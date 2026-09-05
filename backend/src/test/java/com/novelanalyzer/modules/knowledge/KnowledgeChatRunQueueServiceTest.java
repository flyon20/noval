package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.config.KnowledgeProperties;
import com.novelanalyzer.config.KnowledgeRabbitConfig;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatRunQueueService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeChatRunQueueServiceTest {

    @Test
    void shouldTreatMandatoryPublisherReturnAsFailureEvenWhenBrokerConfirms() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                "noval.knowledge.chat.run",
                "knowledge.chat.run"
            ));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq("noval.knowledge.chat.run"),
            eq("knowledge.chat.run"),
            any(KnowledgeChatRunQueueService.ChatRunQueueMessage.class),
            any(CorrelationData.class)
        );
        KnowledgeChatRunQueueService service = new KnowledgeChatRunQueueService(rabbitTemplate, new KnowledgeProperties());

        assertThat(service.publishExecute("run-unroutable")).isFalse();
    }

    @Test
    void shouldPublishExecuteAndCancelMessagesWithOnlyRunIdAndAction() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq("noval.knowledge.chat.run"),
            eq("knowledge.chat.run"),
            any(KnowledgeChatRunQueueService.ChatRunQueueMessage.class),
            any(CorrelationData.class)
        );
        KnowledgeChatRunQueueService service = new KnowledgeChatRunQueueService(rabbitTemplate, new KnowledgeProperties());

        assertThat(service.publishExecute(" run-1 ")).isTrue();
        assertThat(service.publishCancel("run-2")).isTrue();

        ArgumentCaptor<KnowledgeChatRunQueueService.ChatRunQueueMessage> captor =
            ArgumentCaptor.forClass(KnowledgeChatRunQueueService.ChatRunQueueMessage.class);
        verify(rabbitTemplate, times(2)).convertAndSend(
            eq("noval.knowledge.chat.run"),
            eq("knowledge.chat.run"),
            captor.capture(),
            any(CorrelationData.class)
        );
        assertThat(captor.getAllValues()).containsExactly(
            new KnowledgeChatRunQueueService.ChatRunQueueMessage("run-1", "EXECUTE"),
            new KnowledgeChatRunQueueService.ChatRunQueueMessage("run-2", "CANCEL")
        );
    }

    @Test
    void shouldTreatPublisherNackAsFailure() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker-nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            any(String.class),
            any(String.class),
            any(KnowledgeChatRunQueueService.ChatRunQueueMessage.class),
            any(CorrelationData.class)
        );
        KnowledgeChatRunQueueService service = new KnowledgeChatRunQueueService(rabbitTemplate, new KnowledgeProperties());

        assertThat(service.publishExecute("run-nack")).isFalse();
    }

    @Test
    void shouldEnableMandatoryPublishingOnRabbitTemplate() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Jackson2JsonMessageConverter messageConverter = new Jackson2JsonMessageConverter();

        new KnowledgeRabbitConfig()
            .knowledgeIndexRabbitTemplateCustomizer(messageConverter)
            .customize(rabbitTemplate);

        verify(rabbitTemplate).setMessageConverter(messageConverter);
        verify(rabbitTemplate).setMandatory(true);
    }

    @Test
    void shouldNotPublishInvalidChatRunMessage() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        KnowledgeChatRunQueueService service = new KnowledgeChatRunQueueService(rabbitTemplate, new KnowledgeProperties());

        assertThat(service.publish(" ", "EXECUTE")).isFalse();
        assertThat(service.publish("run-1", "RETRY")).isFalse();

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void shouldDeclareDurableDirectQueueAndDeadLetterQueue() {
        KnowledgeProperties properties = new KnowledgeProperties();
        Declarables declarables = new KnowledgeRabbitConfig().knowledgeChatRunRabbitDeclarables(properties);
        List<Object> values = declarables.getDeclarables().stream().map(value -> (Object) value).toList();

        assertThat(values).filteredOn(DirectExchange.class::isInstance).hasSize(2);
        assertThat(values).filteredOn(DirectExchange.class::isInstance)
            .allSatisfy(value -> {
                DirectExchange exchange = (DirectExchange) value;
                assertThat(exchange.isDurable()).isTrue();
                assertThat(exchange.isAutoDelete()).isFalse();
            });

        Queue mainQueue = values.stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .filter(queue -> "noval.knowledge.chat.run".equals(queue.getName()))
            .findFirst()
            .orElseThrow();
        Queue deadLetterQueue = values.stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .filter(queue -> "noval.knowledge.chat.run.dlq".equals(queue.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(mainQueue.isDurable()).isTrue();
        assertThat(mainQueue.getArguments())
            .containsEntry("x-dead-letter-exchange", "noval.knowledge.chat.run.dlx")
            .containsEntry("x-dead-letter-routing-key", "knowledge.chat.run.dlq");
        assertThat(deadLetterQueue.isDurable()).isTrue();
        assertThat(values).filteredOn(Binding.class::isInstance).hasSize(2);
    }

    @Test
    void shouldNotCreateChatRunRabbitInfrastructureWhenQueueIsDisabled() {
        new ApplicationContextRunner()
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withUserConfiguration(KnowledgeRabbitConfig.class)
            .withPropertyValues("app.knowledge.chat-run.queue-enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean("knowledgeChatRunRabbitListenerContainerFactory");
                assertThat(context).doesNotHaveBean("knowledgeChatRunRabbitDeclarables");
            });
    }
}

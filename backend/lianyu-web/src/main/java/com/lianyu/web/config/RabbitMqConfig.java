package com.lianyu.web.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@EnableRabbit
public class RabbitMqConfig {

    public static final String EXCHANGE_LIANYU = "lianyu.exchange";

    // Queue names
    public static final String QUEUE_MEMORY_SUMMARY = "memory.summary.queue";
    public static final String QUEUE_MEMORY_SUMMARY_DLX = "memory.summary.dlq";
    public static final String QUEUE_MESSAGE_ARCHIVE = "message.archive.queue";
    public static final String QUEUE_EVENT_BROADCAST = "event.broadcast.queue";

    // Routing keys
    public static final String RK_MEMORY_SUMMARY = "memory.summary";
    public static final String RK_MEMORY_SUMMARY_DLX = "memory.summary.dlq";
    public static final String RK_MESSAGE_ARCHIVE = "message.archive";
    public static final String RK_EVENT_BROADCAST = "event.broadcast";

    @Value("${lianyu.mq.memory.prefetch:20}")
    private int memoryPrefetch;
    @Value("${lianyu.mq.memory.concurrent-consumers:2}")
    private int memoryConcurrentConsumers;
    @Value("${lianyu.mq.memory.max-concurrent-consumers:8}")
    private int memoryMaxConcurrentConsumers;

    @Bean
    public TopicExchange lianyuExchange() {
        return new TopicExchange(EXCHANGE_LIANYU);
    }

    @Bean
    public Queue memorySummaryQueue() {
        return QueueBuilder.durable(QUEUE_MEMORY_SUMMARY)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", EXCHANGE_LIANYU,
                        "x-dead-letter-routing-key", RK_MEMORY_SUMMARY_DLX
                ))
                .build();
    }

    @Bean
    public Queue memorySummaryDeadLetterQueue() {
        return QueueBuilder.durable(QUEUE_MEMORY_SUMMARY_DLX).build();
    }

    @Bean
    public Queue messageArchiveQueue() {
        return QueueBuilder.durable(QUEUE_MESSAGE_ARCHIVE).build();
    }

    @Bean
    public Queue eventBroadcastQueue() {
        return QueueBuilder.durable(QUEUE_EVENT_BROADCAST).build();
    }

    @Bean
    public Binding memorySummaryBinding() {
        return BindingBuilder.bind(memorySummaryQueue()).to(lianyuExchange()).with(RK_MEMORY_SUMMARY);
    }

    @Bean
    public Binding memorySummaryDeadLetterBinding() {
        return BindingBuilder.bind(memorySummaryDeadLetterQueue()).to(lianyuExchange()).with(RK_MEMORY_SUMMARY_DLX);
    }

    @Bean
    public Binding messageArchiveBinding() {
        return BindingBuilder.bind(messageArchiveQueue()).to(lianyuExchange()).with(RK_MESSAGE_ARCHIVE);
    }

    @Bean
    public Binding eventBroadcastBinding() {
        return BindingBuilder.bind(eventBroadcastQueue()).to(lianyuExchange()).with(RK_EVENT_BROADCAST);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2MessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(Math.max(1, memoryConcurrentConsumers));
        factory.setMaxConcurrentConsumers(Math.max(memoryConcurrentConsumers, memoryMaxConcurrentConsumers));
        factory.setPrefetchCount(Math.max(1, memoryPrefetch));
        // 失败后不回队列，交给 DLQ，避免 poison message 无限重试
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}

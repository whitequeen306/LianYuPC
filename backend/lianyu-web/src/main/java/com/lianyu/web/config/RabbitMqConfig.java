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
    public static final String QUEUE_COMMUNITY_MODERATION = "community.moderation.queue";
    public static final String QUEUE_COMMUNITY_POST_NOTIFY = "community.post.notify.queue";
    public static final String QUEUE_AI_BACKGROUND = "ai.background.queue";
    public static final String QUEUE_AI_BACKGROUND_DLX = "ai.background.dlq";

    // Routing keys
    public static final String RK_MEMORY_SUMMARY = "memory.summary";
    public static final String RK_MEMORY_SUMMARY_DLX = "memory.summary.dlq";
    public static final String RK_MESSAGE_ARCHIVE = "message.archive";
    public static final String RK_EVENT_BROADCAST = "event.broadcast";
    public static final String RK_COMMUNITY_MODERATION = "community.moderation";
    public static final String RK_COMMUNITY_POST_NOTIFY = "community.post.notify";
    public static final String RK_AI_BACKGROUND = "ai.background";
    public static final String RK_AI_BACKGROUND_DLX = "ai.background.dlq";

    @Value("${lianyu.mq.memory.prefetch:20}")
    private int memoryPrefetch;
    @Value("${lianyu.mq.memory.concurrent-consumers:2}")
    private int memoryConcurrentConsumers;
    @Value("${lianyu.mq.memory.max-concurrent-consumers:8}")
    private int memoryMaxConcurrentConsumers;

    @Value("${lianyu.mq.ai-background.prefetch:4}")
    private int aiBackgroundPrefetch;
    @Value("${lianyu.mq.ai-background.concurrent-consumers:2}")
    private int aiBackgroundConcurrentConsumers;
    @Value("${lianyu.mq.ai-background.max-concurrent-consumers:4}")
    private int aiBackgroundMaxConcurrentConsumers;

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
    public Queue aiBackgroundQueue() {
        return QueueBuilder.durable(QUEUE_AI_BACKGROUND)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", EXCHANGE_LIANYU,
                        "x-dead-letter-routing-key", RK_AI_BACKGROUND_DLX
                ))
                .build();
    }

    @Bean
    public Queue aiBackgroundDeadLetterQueue() {
        return QueueBuilder.durable(QUEUE_AI_BACKGROUND_DLX).build();
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
    public Queue communityModerationQueue() {
        return QueueBuilder.durable(QUEUE_COMMUNITY_MODERATION).build();
    }

    @Bean
    public Queue communityPostNotifyQueue() {
        return QueueBuilder.durable(QUEUE_COMMUNITY_POST_NOTIFY).build();
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
    public Binding aiBackgroundBinding() {
        return BindingBuilder.bind(aiBackgroundQueue()).to(lianyuExchange()).with(RK_AI_BACKGROUND);
    }

    @Bean
    public Binding aiBackgroundDeadLetterBinding() {
        return BindingBuilder.bind(aiBackgroundDeadLetterQueue()).to(lianyuExchange()).with(RK_AI_BACKGROUND_DLX);
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
    public Binding communityModerationBinding() {
        return BindingBuilder.bind(communityModerationQueue()).to(lianyuExchange()).with(RK_COMMUNITY_MODERATION);
    }

    @Bean
    public Binding communityPostNotifyBinding() {
        return BindingBuilder.bind(communityPostNotifyQueue()).to(lianyuExchange()).with(RK_COMMUNITY_POST_NOTIFY);
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

    /** 后台 AI：低 prefetch + 低并发，用队列背压控吞吐。 */
    @Bean
    public SimpleRabbitListenerContainerFactory aiBackgroundListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(Math.max(1, aiBackgroundConcurrentConsumers));
        factory.setMaxConcurrentConsumers(
                Math.max(aiBackgroundConcurrentConsumers, aiBackgroundMaxConcurrentConsumers));
        factory.setPrefetchCount(Math.max(1, aiBackgroundPrefetch));
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

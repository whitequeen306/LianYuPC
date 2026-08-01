package com.lianyu.service.ai.background;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiBackgroundPublisher {

    public static final String EXCHANGE = "lianyu.exchange";
    public static final String ROUTING_KEY = "ai.background";

    private final RabbitTemplate rabbitTemplate;

    public void publish(AiBackgroundTask task) {
        if (task == null || task.type() == null) {
            return;
        }
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, task);
        log.info("AI background enqueued: type={}, userId={}, convId={}, postId={}, characterId={}",
                task.type(), task.userId(), task.conversationId(), task.postId(), task.characterId());
    }
}

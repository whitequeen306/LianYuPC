package com.lianyu.service;

import com.lianyu.service.dto.PushNotifyTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushConsumer {

    private final WebPushService webPushService;

    @RabbitListener(
            queues = "event.broadcast.queue",
            concurrency = "${lianyu.mq.push.listener-concurrency:1-4}"
    )
    public void onPushTask(PushNotifyTask task) {
        if (task == null || task.userId() == null) {
            return;
        }
        webPushService.sendToUser(task.userId(), task.title(), task.body(), task.conversationId());
    }
}

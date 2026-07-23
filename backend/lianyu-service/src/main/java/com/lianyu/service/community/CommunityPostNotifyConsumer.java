package com.lianyu.service.community;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityPostNotifyConsumer {

    private final CommunityPushService communityPushService;

    @RabbitListener(queues = "community.post.notify.queue")
    public void onNewPost(CommunityPostNotifyTask task) {
        if (task == null || task.postId() == null) {
            return;
        }
        try {
            communityPushService.broadcastNewPost(task.postId(), task.authorUserId());
        } catch (Exception e) {
            log.warn("Community post notify consumer failed: postId={}, reason={}",
                    task.postId(), e.getMessage());
        }
    }
}

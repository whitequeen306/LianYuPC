package com.lianyu.service.community;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityModerationConsumer {

    private final CommunityModerationService communityModerationService;

    @RabbitListener(queues = "community.moderation.queue")
    public void onModerationTask(CommunityModerationTask task) {
        if (task == null || task.postId() == null) {
            return;
        }
        log.info("Received community moderation task: postId={}", task.postId());
        try {
            communityModerationService.moderatePost(task.postId());
        } catch (Exception e) {
            log.warn("Community moderation failed: postId={}, reason={}", task.postId(), e.getMessage());
            throw e;
        }
    }
}

package com.lianyu.service.community;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Drains legacy moderation queue messages with fast sync rules (no AI).
 */
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
        log.info("Received legacy community moderation task: postId={}", task.postId());
        try {
            communityModerationService.finalizePendingPost(task.postId());
        } catch (Exception e) {
            log.warn("Community moderation finalize failed: postId={}, reason={}",
                    task.postId(), e.getMessage());
        }
    }
}

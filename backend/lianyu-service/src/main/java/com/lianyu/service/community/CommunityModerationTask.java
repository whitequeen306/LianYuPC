package com.lianyu.service.community;

/**
 * RabbitMQ payload for async community post moderation.
 */
public record CommunityModerationTask(Long postId, Long authorUserId) {
}

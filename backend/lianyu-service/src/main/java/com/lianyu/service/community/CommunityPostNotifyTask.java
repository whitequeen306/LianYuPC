package com.lianyu.service.community;

/**
 * RabbitMQ payload: fan-out community post toast to opted-in users.
 */
public record CommunityPostNotifyTask(Long postId, Long authorUserId) {
}

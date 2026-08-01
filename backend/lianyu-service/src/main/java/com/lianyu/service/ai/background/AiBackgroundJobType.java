package com.lianyu.service.ai.background;

/**
 * 后台 AI 任务类型。用户可见聊天 / 首条破冰不走此队列。
 */
public enum AiBackgroundJobType {
    MOMENTS_PEER_COMMENT,
    MOMENTS_AUTHOR_REPLY,
    MOMENTS_POST,
    CHARACTER_DIARY,
    COLD_OPEN_FOLLOWUP,
    CITY_CHANGE_FOLLOWUP,
    VOICE_CALL_SUMMARY
}

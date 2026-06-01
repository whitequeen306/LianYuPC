package com.lianyu.service;

/**
 * 单角色聊天行为参数（由全局默认 + 性格推导 + settings 覆盖合成）
 */
public record CharacterChatBehavior(
        int maxRepliesPerTurn,
        boolean proactiveEnabled,
        int minIdleMinutes,
        double triggerProbability,
        int cooldownMinMinutes,
        int cooldownMaxMinutes,
        String speakingStyle
) {
}

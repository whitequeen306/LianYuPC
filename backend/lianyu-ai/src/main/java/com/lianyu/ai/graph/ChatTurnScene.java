package com.lianyu.ai.graph;

/**
 * AI 回合场景：决定 Graph 中哪些上下文 section 参与组装。
 */
public enum ChatTurnScene {
    SINGLE,
    GROUP,
    MOMENTS,
    DIARY,
    PROACTIVE,
    /** 实时语音通话：短回复、禁心理活动括号；跳过记忆检索/会话摘要/工具以降低首包延迟。 */
    VOICE_CALL;

    public boolean includeRelationship() {
        return this == SINGLE || this == PROACTIVE || this == MOMENTS || this == DIARY || this == VOICE_CALL;
    }

    public boolean includeSessionSummary() {
        return this == SINGLE || this == PROACTIVE;
    }

    public boolean includeTimeCityGoodnight() {
        return this == SINGLE || this == PROACTIVE;
    }

    public boolean includeProactiveRealWorld() {
        return this == PROACTIVE;
    }

    public boolean enableChatTools() {
        return this == SINGLE || this == GROUP || this == DIARY || this == PROACTIVE;
    }

    /** 向量记忆检索（Milvus）较慢；语音通话跳过以提速。 */
    public boolean includeMemory() {
        return this != VOICE_CALL;
    }
}

package com.lianyu.ai.graph;

/**
 * AI 回合场景：决定 Graph 中哪些上下文 section 参与组装。
 */
public enum ChatTurnScene {
    SINGLE,
    GROUP,
    MOMENTS,
    DIARY,
    PROACTIVE;

    public boolean includeRelationship() {
        return this == SINGLE || this == PROACTIVE || this == MOMENTS || this == DIARY;
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
}

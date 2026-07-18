package com.lianyu.ai.graph;

/**
 * ChatTurn Graph 节点 ID。
 */
public final class ChatTurnNodes {

    private ChatTurnNodes() {
    }

    public static final String LOAD_CONTEXT = "loadContext";
    public static final String ASSEMBLE_PROMPT = "assemblePrompt";
    public static final String ASSEMBLE_MESSAGES = "assembleMessages";
    public static final String INVOKE_MODEL = "invokeModel";
}

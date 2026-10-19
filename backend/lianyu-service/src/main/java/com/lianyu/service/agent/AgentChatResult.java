package com.lianyu.service.agent;

/**
 * 一次 agent 阻塞回合的结果（含 usage 与识图描述）。
 *
 * <p>原 `ChatResult` 由 Java 模型链路产生；现在模型调用在 Python，
 * Java 只透传结果字段，避免 token 统计/图片占位丢失。
 */
public record AgentChatResult(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String imageDescription) {

    public static AgentChatResult ofContent(String content) {
        return new AgentChatResult(content, null, null, null, null);
    }
}

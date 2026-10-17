package com.lianyu.service.agent;

/**
 * 一次 agent 回合的结果。
 *
 * <p>为什么要带 usage：AGENT 模式下模型调用发生在 Python 侧，若只回传正文，
 * Java 的 token 统计（`saveAssistantRepliesLimited(..., totalTokens, ...)`）会变成 null，
 * 用量数据静默丢失。这里把 usage 一起带回来。
 *
 * @param content     助手正文（可能为空串）
 * @param totalTokens 总 token；Python 未返回时为 null
 */
public record AgentTurnResult(String content, Integer totalTokens) {

    public boolean isUsable() {
        return content != null && !content.isBlank();
    }
}

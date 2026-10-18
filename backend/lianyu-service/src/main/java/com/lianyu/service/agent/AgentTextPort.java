package com.lianyu.service.agent;

/**
 * 通用阻塞文本端口：把「一次模型调用」本身交给 backend-agent。
 *
 * <p>适用链路：日记生成、朋友圈发帖、朋友圈评论 —— 它们的 system prompt 与
 * 输出解析都由 Java 负责，只有 model 调用这一段可以下沉，以便统一走
 * Python 的熔断/隔舱/显式超时。
 *
 * <p>返回语义：{@code null} 表示「未接管或失败」，调用方必须回退 Java 原链路。
 */
public interface AgentTextPort {

    /**
     * 无历史的快捷形式。
     *
     * @return 模型原始文本；null 表示未接管/失败，调用方应回退
     */
    default String complete(long userId, String systemPrompt, String userText,
                            String provider, String model, double temperature) {
        return complete(userId, systemPrompt, java.util.List.of(), userText,
                provider, model, temperature);
    }

    /**
     * 带历史的完整形式（朋友圈发帖/评论需要在 system 与指令之间插入历史）。
     *
     * @return 模型原始文本；null 表示未接管/失败，调用方应回退
     */
    String complete(long userId,
                    String systemPrompt,
                    java.util.List<com.lianyu.service.dto.MessageDto> history,
                    String userText,
                    String provider,
                    String model,
                    double temperature);
}

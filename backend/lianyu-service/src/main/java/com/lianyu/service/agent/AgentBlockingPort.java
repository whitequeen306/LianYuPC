package com.lianyu.service.agent;

import com.lianyu.service.dto.MessageDto;
import java.util.List;

/**
 * 阻塞式对话端口：群聊 / 语音 / 日记 / 会话摘要 / judge 等「一次性取整段回复」的场景。
 *
 * <p>返回 {@code null} 表示未接管（非 AGENT 模式、agent 不可用、失败），
 * 调用方必须回退到 Java 原实现。实现方**不得抛异常**。
 */
public interface AgentBlockingPort {

    /**
     * @param provider    模型供应商（Java 侧 vault 键）
     * @param model       模型名，null 时用 vault 默认
     * @param temperature 温度
     * @param maxTokens   最大 token
     * @return 回复正文；null 表示回退
     */
    String complete(long userId, List<MessageDto> messages, String provider, String model,
                    Double temperature, Integer maxTokens);
}

package com.lianyu.service.agent;

import com.lianyu.service.dto.MessageDto;
import java.util.List;

/**
 * 阻塞式对话端口：群聊 / 语音 / 日记 / 会话摘要 / judge 等「一次性取整段回复」的场景。
 *
 * <p>返回 {@code null} 表示 agent 不可用或失败；OFF 退役后调用方必须报错，
 * 不再回退 Java 模型。
 */
public interface AgentBlockingPort {

    /**
     * @param provider    模型供应商（Java 侧 vault 键）
     * @param model       模型名，null 时用 vault 默认
     * @param temperature 温度
     * @param maxTokens   最大 token
     * @return 回复与 usage；null 表示失败
     */
    AgentChatResult complete(long userId, List<MessageDto> messages, String provider, String model,
                             Double temperature, Integer maxTokens);
}

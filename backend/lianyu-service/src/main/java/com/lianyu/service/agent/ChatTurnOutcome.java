package com.lianyu.service.agent;

import com.lianyu.service.dto.MessageDto;
import java.util.List;

/**
 * 一次聊天回合的产物：Python 图组装出的 prompt/messages，以及（invoke 时）模型回复。
 *
 * <p>只有 {@code assemble} 会返回 {@code messages}；流式回合不需要它们
 * （消息在 Python 内直接喂给模型），非图片的阻塞回合也不需要。
 * 图片回合例外：识图链路走既有 {@code AgentImagePort}，需要 Java 把
 * prompt + messages 传过去，因此先 assemble 再调图片端口。
 */
public record ChatTurnOutcome(
        String systemPrompt,
        List<MessageDto> messages,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {

    public static ChatTurnOutcome assembled(String systemPrompt, List<MessageDto> messages) {
        return new ChatTurnOutcome(systemPrompt, messages, null, null, null, null);
    }
}

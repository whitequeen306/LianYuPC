package com.lianyu.service.graph;

import com.lianyu.service.dto.ChatResult;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ChatTurnResult {
    String systemPrompt;
    String content;
    Integer promptTokens;
    Integer completionTokens;
    Integer totalTokens;
    String imageDescription;
    /** 本轮模型侧挑中的表情包（公网路径）；非空时落到 ASSISTANT 消息的 image_url。 */
    String stickerUrl;

    public ChatResult toChatResult() {
        return ChatResult.builder()
                .content(content)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .imageDescription(imageDescription)
                .build();
    }
}

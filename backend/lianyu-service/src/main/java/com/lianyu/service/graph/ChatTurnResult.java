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

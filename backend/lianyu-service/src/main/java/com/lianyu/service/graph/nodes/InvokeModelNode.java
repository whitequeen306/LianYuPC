package com.lianyu.service.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnState;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.agent.AgentBlockingPort;
import com.lianyu.service.agent.AgentChatResult;
import com.lianyu.service.agent.AgentImagePort;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.tools.ChatToolContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 阻塞模型调用：全部交给 agent 端口（文本 / 图片两条）。
 *
 * <p>OFF 退役后 Java 不再有模型调用；端口返回 null 即报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvokeModelNode implements NodeAction {

    private final AgentBlockingPort agentBlockingPort;
    private final AgentImagePort agentImagePort;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        ChatTurnState turn = new ChatTurnState(state);
        if (turn.streaming()) {
            log.info("node=invokeModel skipped streaming=true");
            return Map.of();
        }

        Character character = (Character) turn.characterRef();
        List<MessageDto> messages = state.value(ChatTurnKeys.MESSAGE_DTOS, List.class).orElse(List.of());
        AiChatRequest request = buildRequest(state, turn, character, messages);

        boolean hasImage = hasImage(request);
        AgentChatResult result;
        try {
            result = hasImage
                    ? agentImagePort.completeIfActive(
                            turn.userId(), turn.systemPrompt(), messages, request.getImageUrl(),
                            request.getProvider(), request.getModel(),
                            request.getMaxTokens() == null ? 1024 : request.getMaxTokens())
                    : agentBlockingPort.complete(
                            turn.userId(), messages, request.getProvider(), request.getModel(),
                            request.getTemperature(), request.getMaxTokens());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试", e);
        }
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "对方没有回复内容，请重试");
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(ChatTurnKeys.ASSISTANT_RAW, result.content());
        updates.put(ChatTurnKeys.ASSISTANT_FINAL, result.content());
        updates.put(ChatTurnKeys.TOTAL_TOKENS, result.totalTokens());
        updates.put(ChatTurnKeys.PROMPT_TOKENS, result.promptTokens());
        updates.put(ChatTurnKeys.COMPLETION_TOKENS, result.completionTokens());
        if (result.imageDescription() != null && !result.imageDescription().isBlank()) {
            updates.put(ChatTurnKeys.IMAGE_DESCRIPTION, result.imageDescription());
        }
        log.info("node=invokeModel mode=blocking hasImage={} chars={}",
                hasImage, result.content().length());
        return updates;
    }

    public static AiChatRequest buildRequest(
            OverAllState state,
            ChatTurnState turn,
            Character character,
            List<MessageDto> messages
    ) {
        AiChatRequest request = new AiChatRequest();
        request.setProvider(state.value(ChatTurnKeys.PROVIDER, String.class).orElse(null));
        request.setModel(state.value(ChatTurnKeys.MODEL, String.class).orElse(null));
        request.setVisionModel(state.value(ChatTurnKeys.VISION_MODEL, String.class).orElse(null));
        request.setTemperature(state.value(ChatTurnKeys.TEMPERATURE, Double.class).orElse(null));
        request.setMessages(messages);
        request.setExpectedLanguage(turn.outputLanguage());
        String imageUrl = turn.imageUrl();
        if (imageUrl != null && !imageUrl.isBlank()) {
            request.setImageUrl(imageUrl);
        }
        if (character != null) {
            ChatToolContext.bindTo(request, character);
        }
        return request;
    }

    private static boolean hasImage(AiChatRequest request) {
        return request.getImageUrl() != null && !request.getImageUrl().isBlank();
    }
}

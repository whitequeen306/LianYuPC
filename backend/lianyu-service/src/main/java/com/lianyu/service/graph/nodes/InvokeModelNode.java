package com.lianyu.service.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnState;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.tools.ChatToolContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Blocking model invocation (tools / vision / language gate) via {@link AiChatService}.
 * Streaming is bridged by {@link com.lianyu.service.graph.ChatTurnFacade} after assembly nodes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvokeModelNode implements NodeAction {

    private final AiChatService aiChatService;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        ChatTurnState turn = new ChatTurnState(state);
        if (turn.streaming()) {
            // Streaming path completes outside this node; keep state intact.
            log.info("node=invokeModel skipped streaming=true");
            return Map.of();
        }

        Character character = (Character) turn.characterRef();
        List<MessageDto> messages = state.value(ChatTurnKeys.MESSAGE_DTOS, List.class).orElse(List.of());
        AiChatRequest request = buildRequest(state, turn, character, messages);

        ChatResult result = hasImage(request)
                ? aiChatService.chatImageBlocking(turn.userId(), request)
                : aiChatService.chatBlocking(turn.userId(), request);

        Map<String, Object> updates = new HashMap<>();
        updates.put(ChatTurnKeys.ASSISTANT_RAW, result.getContent());
        updates.put(ChatTurnKeys.ASSISTANT_FINAL, result.getContent());
        updates.put(ChatTurnKeys.TOTAL_TOKENS, result.getTotalTokens());
        updates.put(ChatTurnKeys.PROMPT_TOKENS, result.getPromptTokens());
        updates.put(ChatTurnKeys.COMPLETION_TOKENS, result.getCompletionTokens());
        log.info("node=invokeModel mode=blocking hasImage={} chars={}",
                hasImage(request),
                result.getContent() != null ? result.getContent().length() : 0);
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

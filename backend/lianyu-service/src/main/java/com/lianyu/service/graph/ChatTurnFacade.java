package com.lianyu.service.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.ai.graph.ChatTurnState;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.graph.nodes.InvokeModelNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Outer adapter entry for ChatTurn Graph. Owns resilience boundary for callers;
 * SSE / persistence callbacks remain with ConversationService etc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTurnFacade {

    private final CompiledGraph chatTurnGraph;
    private final AiChatService aiChatService;
    private final ChatTurnPromptAssembler promptAssembler;

    public String assembleSystemPrompt(ChatTurnCommand command) {
        OverAllState state = runAssemble(command);
        return new ChatTurnState(state).systemPrompt();
    }

    public ChatTurnResult invokeBlocking(ChatTurnCommand command) {
        Map<String, Object> inputs = toInputs(command, false);
        Optional<OverAllState> result = chatTurnGraph.invoke(inputs);
        OverAllState state = result.orElseThrow(() ->
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "ChatTurn graph produced no state"));
        ChatTurnState turn = new ChatTurnState(state);
        return ChatTurnResult.builder()
                .systemPrompt(turn.systemPrompt())
                .content(turn.assistantFinal())
                .promptTokens(state.value(ChatTurnKeys.PROMPT_TOKENS, Integer.class).orElse(null))
                .completionTokens(state.value(ChatTurnKeys.COMPLETION_TOKENS, Integer.class).orElse(null))
                .totalTokens(state.value(ChatTurnKeys.TOTAL_TOKENS, Integer.class).orElse(null))
                .build();
    }

    /**
     * Assemble prompt/messages via Graph, then stream through {@link AiChatService}.
     */
    public SseEmitter invokeStream(ChatTurnCommand command, AiChatService.StreamCallback callback) {
        OverAllState state = runAssemble(command);
        ChatTurnState turn = new ChatTurnState(state);
        Character character = (Character) turn.characterRef();
        @SuppressWarnings("unchecked")
        List<MessageDto> messages = state.value(ChatTurnKeys.MESSAGE_DTOS, List.class).orElse(List.of());
        AiChatRequest request = InvokeModelNode.buildRequest(state, turn, character, messages);
        boolean hasImage = request.getImageUrl() != null && !request.getImageUrl().isBlank();
        log.info("ChatTurn stream assembled: scene={} messages={} hasImage={}",
                turn.scene(), messages.size(), hasImage);
        return hasImage
                ? aiChatService.chatImageStream(command.getUserId(), request, callback)
                : aiChatService.chatStream(command.getUserId(), request, callback);
    }

    /**
     * Convenience for Moments/Diary/Group that only need scene-aware system prompt.
     */
    public String assembleSystemPrompt(
            ChatTurnScene scene,
            Long userId,
            Long conversationId,
            Character character,
            String userInputForLang,
            String lastUserMessageForMemory,
            String extraSystemSuffix,
            ChatTurnPromptAssembler.GroupExtras groupExtras
    ) {
        return promptAssembler.assemble(
                scene,
                userId,
                conversationId,
                character,
                userInputForLang,
                lastUserMessageForMemory,
                extraSystemSuffix,
                groupExtras
        ).systemPrompt();
    }

    private OverAllState runAssemble(ChatTurnCommand command) {
        Map<String, Object> inputs = toInputs(command, true);
        Optional<OverAllState> result = chatTurnGraph.invoke(inputs);
        return result.orElseThrow(() ->
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "ChatTurn assemble produced no state"));
    }

    private Map<String, Object> toInputs(ChatTurnCommand command, boolean streaming) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(ChatTurnKeys.SCENE, command.getScene() != null ? command.getScene() : ChatTurnScene.SINGLE);
        inputs.put(ChatTurnKeys.USER_ID, command.getUserId());
        inputs.put(ChatTurnKeys.CONVERSATION_ID, command.getConversationId());
        if (command.getCharacter() != null) {
            inputs.put(ChatTurnKeys.CHARACTER_ID, command.getCharacter().getId());
            inputs.put(ChatTurnKeys.CHARACTER_REF, command.getCharacter());
            inputs.put(ChatTurnKeys.TOOL_CHARACTER_SETTINGS, command.getCharacter().getSettings());
        }
        inputs.put(ChatTurnKeys.PROVIDER, command.getProvider());
        inputs.put(ChatTurnKeys.MODEL, command.getModel());
        inputs.put(ChatTurnKeys.TEMPERATURE, command.getTemperature());
        inputs.put(ChatTurnKeys.RAW_USER_TEXT, command.getRawUserText());
        inputs.put(ChatTurnKeys.MODEL_USER_TEXT, command.getModelUserText());
        inputs.put(ChatTurnKeys.IMAGE_URL, command.getImageUrl());
        inputs.put(ChatTurnKeys.CURRENT_USER_MSG_ID, command.getCurrentUserMsgId());
        inputs.put(ChatTurnKeys.HISTORY_MESSAGES, command.getHistoryMessages());
        inputs.put(ChatTurnKeys.PREPARED_MESSAGES, command.getPreparedMessages());
        inputs.put(ChatTurnKeys.EXTRA_SYSTEM_SUFFIX, command.getExtraSystemSuffix());
        inputs.put(ChatTurnKeys.GROUP_EXTRAS, command.getGroupExtras());
        inputs.put(ChatTurnKeys.STREAMING, streaming);
        inputs.put(ChatTurnKeys.STREAM_SINK, command.getStreamSink());
        if (command.getToolCharacterSettings() != null) {
            inputs.put(ChatTurnKeys.TOOL_CHARACTER_SETTINGS, command.getToolCharacterSettings());
        }
        return inputs;
    }
}

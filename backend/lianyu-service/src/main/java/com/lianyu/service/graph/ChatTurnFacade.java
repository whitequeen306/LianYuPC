package com.lianyu.service.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.ai.graph.ChatTurnState;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.agent.PromptAssemblyRequest;
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
    private final com.lianyu.service.agent.PromptAssemblyPort promptAssemblyPort;
    private final com.lianyu.service.agent.AgentStreamPort agentStreamPort;
    private final com.lianyu.service.agent.AgentImagePort agentImagePort;
    private final com.lianyu.service.support.OutputLanguageService outputLanguageService;
    private final com.lianyu.service.tools.TimeTool timeTool;
    private final com.lianyu.service.tools.ToolManager toolManager;
    private final com.lianyu.service.character.CharacterChatBehaviorResolver behaviorResolver;

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
                .imageDescription(state.value(ChatTurnKeys.IMAGE_DESCRIPTION, String.class).orElse(null))
                .build();
    }

    /**
     * Assemble prompt/messages via Graph, then stream through the agent ports.
     */
    public SseEmitter invokeStream(ChatTurnCommand command, com.lianyu.service.agent.AgentStreamCallback callback) {
        OverAllState state = runAssemble(command);
        ChatTurnState turn = new ChatTurnState(state);
        Character character = (Character) turn.characterRef();
        @SuppressWarnings("unchecked")
        List<MessageDto> messages = state.value(ChatTurnKeys.MESSAGE_DTOS, List.class).orElse(List.of());
        AiChatRequest request = InvokeModelNode.buildRequest(state, turn, character, messages);
        boolean hasImage = request.getImageUrl() != null && !request.getImageUrl().isBlank();
        log.info("ChatTurn stream assembled: scene={} messages={} hasImage={}",
                turn.scene(), messages.size(), hasImage);
        try {
            if (hasImage) {
                SseEmitter viaAgent = agentImagePort.streamIfActive(
                        command.getUserId(), turn.systemPrompt(), messages, request.getImageUrl(),
                        request.getProvider(), request.getModel(),
                        request.getMaxTokens() == null ? 1024 : request.getMaxTokens(), callback);
                if (viaAgent == null) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                            "图片消息发送失败，请稍后再试");
                }
                log.info("ChatTurn image stream served by agent runtime: scene={}", turn.scene());
                return viaAgent;
            }
            SseEmitter agentEmitter = agentStreamPort.streamIfActive(
                    command.getUserId(),
                    turn.systemPrompt(),
                    request.getMessages(),
                    request.getProvider(),
                    request.getModel(),
                    request.getExpectedLanguage(),
                    character == null ? null : character.getId(),
                    request.getMaxTokens() == null ? 1024 : request.getMaxTokens(),
                    callback);
            if (agentEmitter == null) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                        "消息发送失败，请稍后再试");
            }
            log.info("ChatTurn stream served by agent runtime: scene={}", turn.scene());
            return agentEmitter;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Agent stream dispatch failed: {}", e.toString());
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试", e);
        }
    }

    /**
     * Convenience for Moments/Diary/Group/Voice/Proactive that only need a scene-aware system prompt.
     *
     * <p>只调 agent；失败直接抛错（OFF 退役后不再静默回退 Java 组装）。
     */
    public String assembleSystemPrompt(
            ChatTurnScene scene,
            Long userId,
            Long conversationId,
            Character character,
            String userInputForLang,
            String lastUserMessageForMemory,
            String extraSystemSuffix,
            ChatTurnGroupExtras groupExtras
    ) {
        String outputLanguage = outputLanguageService.resolveForRequest(userId, userInputForLang);
        boolean includeTime = scene.includeTime();
        String timeFact = includeTime ? timeTool.readCurrentTimeFact() : null;
        boolean tools = scene.enableChatTools();
        int behaviorMax = behaviorResolver.resolve(character).maxRepliesPerTurn();
        int maxPieces = groupExtras != null && groupExtras.maxPieces() > 0
                ? groupExtras.maxPieces()
                : behaviorMax;
        String memoryText = lastUserMessageForMemory == null || lastUserMessageForMemory.isBlank()
                ? userInputForLang
                : lastUserMessageForMemory;
        PromptAssemblyRequest request = new PromptAssemblyRequest(
                userId,
                character.getId(),
                conversationId,
                character.getName(),
                character.getSettings(),
                character.getPromptTemplate(),
                scene.name(),
                outputLanguage,
                afterBaseOf(scene),
                includeTime,
                timeFact,
                tools,
                scene != ChatTurnScene.VOICE_CALL
                        && com.lianyu.service.character.CharacterPreferenceResolver
                                .showInnerThoughts(character),
                maxPieces,
                "",
                tools ? toolManager.promptHintFlags(userId) : Map.of("chat", false),
                memoryText,
                extraSystemSuffix,
                groupExtras == null ? 0 : groupExtras.maxPieces(),
                groupExtras == null ? "" : groupExtras.otherCharactersLine(),
                groupExtras == null ? "" : groupExtras.mentionCtx());
        String prompt;
        try {
            prompt = promptAssemblyPort.assembleIfEnabled(request);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "prompt 组装失败，请稍后再试", e);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "prompt 组装失败，请稍后再试");
        }
        return prompt;
    }

    private static String afterBaseOf(ChatTurnScene scene) {
        if (scene.includeTimeCityGoodnight()) {
            return "full";
        }
        if (scene == ChatTurnScene.MOMENTS || scene == ChatTurnScene.DIARY) {
            return "style_only";
        }
        if (scene == ChatTurnScene.VOICE_CALL) {
            return "time_only";
        }
        return "none";
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
        inputs.put(ChatTurnKeys.VISION_MODEL, command.getVisionModel());
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

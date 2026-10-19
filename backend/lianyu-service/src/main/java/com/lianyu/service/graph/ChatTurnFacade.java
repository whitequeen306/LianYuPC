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
import com.lianyu.service.ai.VisionChatService;
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
    private final VisionChatService visionChatService;
    private final ChatTurnPromptAssembler promptAssembler;

    /**
     * 单聊流式的 agent 端口（可选）。非 final + required=false：
     * 单测与裁剪部署没有 web 层时保持 null，等价 OFF 模式。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.AgentStreamPort agentStreamPort;

    /** SHADOW 模式下的 prompt 级影子比对（未接线时为 null，等价关闭）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.PromptShadowPort promptShadowPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.tools.TimeTool shadowTimeTool;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.tools.ToolManager shadowToolManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.character.CharacterChatBehaviorResolver shadowBehaviorResolver;

    /** 影子比对需要完整角色（settings 决定行为），按 id 回库取一次 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.dao.mapper.CharacterMapper shadowCharacterMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.character.CharacterStateService shadowCharacterStateService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.AgentImagePort agentImagePort;

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
     * Assemble prompt/messages via Graph, then stream through {@link AiChatService}.
     */
    public SseEmitter invokeStream(ChatTurnCommand command, AiChatService.StreamCallback callback) {
        OverAllState state = runAssemble(command);
        ChatTurnState turn = new ChatTurnState(state);
        Character character = (Character) turn.characterRef();
        @SuppressWarnings("unchecked")
        List<MessageDto> messages = state.value(ChatTurnKeys.MESSAGE_DTOS, List.class).orElse(List.of());
        AiChatRequest request = InvokeModelNode.buildRequest(state, turn, character, messages);
        dispatchPromptShadow(command, state, turn, character, messages);
        boolean hasImage = request.getImageUrl() != null && !request.getImageUrl().isBlank();
        log.info("ChatTurn stream assembled: scene={} messages={} hasImage={}",
                turn.scene(), messages.size(), hasImage);
        if (hasImage) {
            // Strangler: 图片消息优先交给 agent（Java 负责取图并转 base64）
            if (agentImagePort != null) {
                try {
                    SseEmitter viaAgent = agentImagePort.streamIfActive(
                            command.getUserId(), turn.systemPrompt(), messages, request.getImageUrl(),
                            request.getProvider(), request.getModel(),
                            request.getMaxTokens() == null ? 1024 : request.getMaxTokens(), callback);
                    if (viaAgent != null) {
                        log.info("ChatTurn image stream served by agent runtime: scene={}", turn.scene());
                        return viaAgent;
                    }
                } catch (Exception e) {
                    log.warn("Agent image dispatch failed, fallback to Java: {}", e.toString());
                }
            }
            return visionChatService.chatImageStream(command.getUserId(), request, callback);
        }
        // Strangler: AGENT 模式下纯文本流交给 Python；返回 null 表示未接管 → 走 Java 原链路
        if (agentStreamPort != null) {
            try {
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
                if (agentEmitter != null) {
                    log.info("ChatTurn stream served by agent runtime: scene={}", turn.scene());
                    return agentEmitter;
                }
            } catch (Exception e) {
                log.warn("Agent stream dispatch failed, fallback to Java: {}", e.toString());
            }
        }
        return aiChatService.chatStream(command.getUserId(), request, callback);
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

    /**
     * SHADOW 模式下把「Java 刚组装的 prompt」与「Python 组装的 prompt」比对。
     * 任何异常都在端口实现里被吞掉；此处再包一层以防端口本身抛错。
     */
    /**
     * SHADOW 模式下的 prompt 级影子比对：把 Java 刚组装的 prompt 与 Python 自读组装的结果比对。
     *
     * <p>快照必须包含 Java 组装时用到的**全部输入**，否则会出现「稳定少一段」的假阳性：
     * 工具提示、城市、气泡上限、场景分支都曾因为漏传而产生 100% 的假漂移。
     */
    private void dispatchPromptShadow(ChatTurnCommand command, OverAllState state,
                                      ChatTurnState turn, Character characterRef,
                                      List<MessageDto> messages) {
        if (promptShadowPort == null || characterRef == null) {
            return;
        }
        try {
            Character character = resolveFullCharacter(characterRef, state);
            String memoryContext = joinBlocks(
                    state.value(ChatTurnKeys.MEMORY_BLOCK, String.class).orElse(""),
                    state.value(ChatTurnKeys.RELATIONSHIP_BLOCK, String.class).orElse(""),
                    state.value(ChatTurnKeys.SESSION_SUMMARY_BLOCK, String.class).orElse(""));
            String timeFact = shadowTimeTool != null ? shadowTimeTool.readCurrentTimeFact() : null;
            Map<String, Object> settings = character.getSettings();
            String city = com.lianyu.service.character.CharacterCitySettingsService.MODE_REAL
                    .equals(com.lianyu.service.character.CharacterCitySettingsService
                            .resolveCityMode(settings))
                    ? com.lianyu.service.character.CharacterCitySettingsService.resolveRealCity(settings)
                    : null;
            String afterBase = switch (turn.scene()) {
                case MOMENTS, DIARY -> "style_only";
                case VOICE_CALL -> "time_only";
                default -> "full";
            };
            int maxPieces = shadowBehaviorResolver != null
                    ? shadowBehaviorResolver.resolve(character).maxRepliesPerTurn()
                    : 2;
            com.lianyu.service.agent.AssemblySnapshot snapshot =
                    new com.lianyu.service.agent.AssemblySnapshot(
                            character.getName(),
                            character.getSettings(),
                            character.getPromptTemplate(),
                            memoryContext,
                            "",
                            "",
                            turn.outputLanguage(),
                            turn.scene().enableChatTools(),
                            com.lianyu.service.character.CharacterPreferenceResolver
                                    .showInnerThoughts(character),
                            maxPieces,
                            turn.scene().includeTime(),
                            timeFact,
                            city,
                            afterBase,
                            true,
                            turn.scene().name(),
                            character.getId(),
                            command.getConversationId(),
                            character.getOwnerUserId(),
                            turn.scene().enableChatTools() && shadowToolManager != null
                                    ? shadowToolManager.promptHintFlags(command.getUserId())
                                    : java.util.Map.of("chat", false),
                            turn.rawUserText());

            promptShadowPort.compareAsync(command.getUserId(), snapshot, turn.systemPrompt());
        } catch (Exception e) {
            log.debug("Prompt shadow dispatch failed: {}", e.toString());
        }
    }

    /**
     * 图状态里的 characterRef 是精简引用（settings 可能为空），行为解析会因此回落到默认值。
     * 用数据库里的完整角色还原，保证与组装时同源。
     */
    private Character resolveFullCharacter(Character characterRef, OverAllState state) {
        Character probe = new Character();
        probe.setId(characterRef.getId());
        probe.setName(characterRef.getName());
        probe.setOwnerUserId(characterRef.getOwnerUserId());
        probe.setPromptTemplate(characterRef.getPromptTemplate());
        Map<String, Object> settings = characterRef.getSettings();
        if ((settings == null || settings.isEmpty()) && shadowCharacterMapper != null
                && characterRef.getId() != null) {
            Character full = shadowCharacterMapper.selectById(characterRef.getId());
            if (full != null) {
                settings = full.getSettings();
                probe.setPromptTemplate(full.getPromptTemplate());
                probe.setOwnerUserId(full.getOwnerUserId());
            }
        }
        if (settings == null || settings.isEmpty()) {
            settings = state.value(ChatTurnKeys.TOOL_CHARACTER_SETTINGS, Map.class).orElse(null);
        }
        probe.setSettings(settings);
        return probe;
    }
    private static String joinBlocks(String a, String b, String c) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{a, b, c}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(part);
            }
        }
        return sb.toString();
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

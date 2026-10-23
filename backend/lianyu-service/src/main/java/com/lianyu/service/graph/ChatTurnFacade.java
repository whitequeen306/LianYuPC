package com.lianyu.service.graph;

import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Message;
import com.lianyu.service.agent.AgentChatResult;
import com.lianyu.service.agent.AgentImagePort;
import com.lianyu.service.agent.AgentStreamCallback;
import com.lianyu.service.agent.ChatTurnOutcome;
import com.lianyu.service.agent.ChatTurnPort;
import com.lianyu.service.agent.ChatTurnRequest;
import com.lianyu.service.agent.PromptAssemblyRequest;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import com.lianyu.service.tools.ToolManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天回合的外层适配器：**取事实 → 交给 Python 的 LangGraph → 返回结果**。
 *
 * <p>重构前它是 Spring AI Alibaba Graph 的调用方（`chatTurnGraph.invoke(...)`）；
 * 现在图在 `backend-agent/app/chat_turn.py`。Java 侧保留的只有编排**不该**负责的事：
 * <ul>
 *   <li>把场景名翻译成开关事实（{@link ChatTurnScene} 的方法）</li>
 *   <li>读时间/工具开关/回复条数/语言（这些是配置与本地时钟，不是 prompt 文本）</li>
 *   <li>Jasypt 解密与 SSE 生命周期（分别由 web 侧适配器与调用方持有）</li>
 * </ul>
 *
 * <p>这里**不接收也不传递 systemPrompt 或拼好的消息列表**：
 * 一旦能传，组装逻辑就会悄悄回到 Java。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTurnFacade {

    private final ChatTurnPort chatTurnPort;
    private final com.lianyu.service.agent.PromptAssemblyPort promptAssemblyPort;
    private final AgentImagePort agentImagePort;
    private final OutputLanguageService outputLanguageService;
    private final TimeTool timeTool;
    private final ToolManager toolManager;
    private final com.lianyu.service.character.CharacterChatBehaviorResolver behaviorResolver;

    // ─────────────────────────── 主聊天回合 ───────────────────────────

    public ChatTurnResult invokeBlocking(ChatTurnCommand command) {
        ChatTurnRequest request = toRequest(command);
        String imageUrl = command.getImageUrl();
        if (imageUrl != null && !imageUrl.isBlank()) {
            return invokeBlockingImage(command, request, imageUrl);
        }
        ChatTurnOutcome outcome = chatTurnPort.invoke(request);
        if (outcome == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试");
        }
        if (outcome.content() == null || outcome.content().isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "对方没有回复内容，请重试");
        }
        return ChatTurnResult.builder()
                .systemPrompt(outcome.systemPrompt())
                .content(outcome.content())
                .promptTokens(outcome.promptTokens())
                .completionTokens(outcome.completionTokens())
                .totalTokens(outcome.totalTokens())
                .build();
    }

    /**
     * 流式回合：文本直接交给 LangGraph 端点；图片仍需先组装出 prompt/messages，
     * 再走既有的识图端口（识图 + 生成两段式由 Python 图片链路负责）。
     */
    public SseEmitter invokeStream(ChatTurnCommand command, AgentStreamCallback callback) {
        ChatTurnRequest request = toRequest(command);
        String imageUrl = command.getImageUrl();
        boolean hasImage = imageUrl != null && !imageUrl.isBlank();
        try {
            if (hasImage) {
                return invokeImageStream(command, request, imageUrl, callback);
            }
            SseEmitter emitter = chatTurnPort.stream(request, callback);
            if (emitter == null) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                        "消息发送失败，请稍后再试");
            }
            log.info("ChatTurn stream served by LangGraph runtime: scene={}", command.getScene());
            return emitter;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Chat turn dispatch failed: {}", e.toString());
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试", e);
        }
    }

    private ChatTurnResult invokeBlockingImage(ChatTurnCommand command,
                                               ChatTurnRequest request,
                                               String imageUrl) {
        ChatTurnOutcome assembled = chatTurnPort.assemble(request);
        if (assembled == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试");
        }
        AgentChatResult result = agentImagePort.completeIfActive(
                command.getUserId(), assembled.systemPrompt(), assembled.messages(), imageUrl,
                command.getProvider(), command.getModel(), 1024);
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "对方没有回复内容，请重试");
        }
        return ChatTurnResult.builder()
                .systemPrompt(assembled.systemPrompt())
                .content(result.content())
                .promptTokens(result.promptTokens())
                .completionTokens(result.completionTokens())
                .totalTokens(result.totalTokens())
                .imageDescription(result.imageDescription())
                .build();
    }

    private SseEmitter invokeImageStream(ChatTurnCommand command,
                                         ChatTurnRequest request,
                                         String imageUrl,
                                         AgentStreamCallback callback) {
        ChatTurnOutcome assembled = chatTurnPort.assemble(request);
        if (assembled == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片消息发送失败，请稍后再试");
        }
        SseEmitter viaAgent = agentImagePort.streamIfActive(
                command.getUserId(), assembled.systemPrompt(), assembled.messages(), imageUrl,
                command.getProvider(), command.getModel(), 1024, callback);
        if (viaAgent == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片消息发送失败，请稍后再试");
        }
        log.info("ChatTurn image stream served by agent runtime: scene={}", command.getScene());
        return viaAgent;
    }

    // ─────────────────────────── system prompt（只组装） ───────────────────────────

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
                        && CharacterPreferenceResolver.showInnerThoughts(character),
                maxPieces,
                "",
                tools ? toolManager.promptHintFlags(userId, character) : Map.of("chat", false),
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

    // ─────────────────────────── 命令 → 原始输入 ───────────────────────────

    private ChatTurnRequest toRequest(ChatTurnCommand command) {
        Character character = command.getCharacter();
        ChatTurnScene scene = command.getScene() != null ? command.getScene() : ChatTurnScene.SINGLE;
        ChatTurnGroupExtras groupExtras = command.getGroupExtras();
        String outputLanguage = outputLanguageService.resolveForRequest(
                command.getUserId(), command.getRawUserText());
        boolean includeTime = scene.includeTime();
        boolean enableTools = scene.enableChatTools();
        int behaviorMax = character == null
                ? 0
                : behaviorResolver.resolve(character).maxRepliesPerTurn();
        int maxPieces = groupExtras != null && groupExtras.maxPieces() > 0
                ? groupExtras.maxPieces()
                : behaviorMax;
        // 开关事实：`computer` = 桌面能力在线 && 该角色被允许操控电脑。
        // 打一行 debug 是为了让「为什么这个角色没有桌面能力」可排查（两个闸门哪个关的）。
        Map<String, Object> toolFlags = enableTools
                ? toolManager.promptHintFlags(command.getUserId(), character)
                : Map.of("chat", false);
        log.debug("ChatTurn toolFlags: scene={} characterId={} desktopEnabled={} flags={}",
                scene, character == null ? null : character.getId(),
                CharacterPreferenceResolver.desktopEnabled(character), toolFlags);
        return ChatTurnRequest.builder()
                .userId(command.getUserId() == null ? 0L : command.getUserId())
                .conversationId(command.getConversationId())
                .characterId(character == null ? null : character.getId())
                .characterName(character == null ? null : character.getName())
                .settings(character == null ? Map.of() : character.getSettings())
                .promptTemplate(character == null ? null : character.getPromptTemplate())
                .scene(scene.name())
                .outputLanguage(outputLanguage)
                .userText(command.getModelUserText())
                .rawUserText(command.getRawUserText())
                .history(toHistory(command.getHistoryMessages()))
                .preparedMessages(toPrepared(command.getPreparedMessages()))
                .currentUserMessageId(command.getCurrentUserMsgId())
                .timeFact(includeTime ? timeTool.readCurrentTimeFact() : null)
                .toolFlags(toolFlags)
                .enableChatTools(enableTools)
                .showInnerThoughts(scene != ChatTurnScene.VOICE_CALL
                        && (character == null
                            || CharacterPreferenceResolver.showInnerThoughts(character)))
                .maxPieces(maxPieces)
                .groupMaxPieces(groupExtras == null ? 0 : groupExtras.maxPieces())
                .groupOtherCharacters(groupExtras == null ? "" : groupExtras.otherCharactersLine())
                .groupMention(groupExtras == null ? "" : groupExtras.mentionCtx())
                .extraSystemSuffix(command.getExtraSystemSuffix())
                .prefetchRealWorld(true)
                .provider(command.getProvider())
                .model(command.getModel())
                .temperature(command.getTemperature())
                .maxTokens(null)
                .expectedLanguage(outputLanguage)
                .build();
    }

    /** 历史消息 → 纯数据。取文本、图片占位、语音过滤全部在 Python 内完成。 */
    private static List<Map<String, Object>> toHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(history.size());
        for (Message msg : history) {
            if (msg == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", msg.getId());
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
            item.put("contextContent", msg.getContextContent());
            item.put("imageUrl", msg.getImageUrl());
            item.put("audioUrl", msg.getAudioUrl());
            out.add(item);
        }
        return out;
    }

    /** 群聊主动发言的预置消息 → 纯数据（system 那条由 Python 用组装结果替换）。 */
    private static List<Map<String, Object>> toPrepared(List<MessageDto> prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(prepared.size());
        for (MessageDto dto : prepared) {
            if (dto == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", dto.getRole());
            item.put("content", dto.getContent());
            item.put("imageUrl", dto.getImageUrl());
            out.add(item);
        }
        return out;
    }
}

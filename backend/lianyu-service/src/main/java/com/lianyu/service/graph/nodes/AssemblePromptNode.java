package com.lianyu.service.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.ai.graph.ChatTurnState;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.graph.ChatTurnPromptAssembler;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssemblePromptNode implements NodeAction {

    private final ChatTurnPromptAssembler promptAssembler;

    /** AGENT 模式下由 Python 接管组装；为 null 或返回 null 时回退 Java。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.PromptAssemblyPort promptAssemblyPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.tools.ToolManager toolManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.character.CharacterChatBehaviorResolver behaviorResolver;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        ChatTurnState turn = new ChatTurnState(state);
        Character character = (Character) turn.characterRef();
        if (character == null) {
            throw new IllegalStateException("ChatTurn missing characterRef");
        }
        ChatTurnScene scene = turn.scene();
        ChatTurnPromptAssembler.GroupExtras groupExtras =
                state.value(ChatTurnKeys.GROUP_EXTRAS, ChatTurnPromptAssembler.GroupExtras.class).orElse(null);

        ChatTurnPromptAssembler.AssembledPrompt assembled = promptAssembler.assemble(
                scene,
                turn.userId(),
                turn.conversationId(),
                character,
                turn.rawUserText(),
                turn.modelUserText(),
                turn.extraSystemSuffix(),
                groupExtras);

        String systemPrompt = assembled.systemPrompt();
        String agentPrompt = tryAgentAssembly(turn, character, scene, assembled);
        if (agentPrompt != null) {
            systemPrompt = agentPrompt;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(ChatTurnKeys.SYSTEM_PROMPT, systemPrompt);
        updates.put(ChatTurnKeys.MEMORY_BLOCK, assembled.memoryBlock());
        updates.put(ChatTurnKeys.RELATIONSHIP_BLOCK, assembled.relationshipBlock());
        updates.put(ChatTurnKeys.SESSION_SUMMARY_BLOCK, assembled.sessionSummaryBlock());
        updates.put(ChatTurnKeys.OUTPUT_LANGUAGE, assembled.outputLanguage());
        log.info("node=assemblePrompt scene={} characterId={} promptChars={} source={}",
                scene, character.getId(), systemPrompt.length(),
                agentPrompt != null ? "agent" : "java");
        return updates;
    }

    /**
     * AGENT 模式下让 Python 组装（它自己读记忆/关系/情绪/摘要）。
     * 任何失败都返回 null —— 调用方用 Java 结果兜底。
     */
    private String tryAgentAssembly(ChatTurnState turn, Character character,
                                    ChatTurnScene scene,
                                    ChatTurnPromptAssembler.AssembledPrompt assembled) {
        if (promptAssemblyPort == null) {
            return null;
        }
        try {
            String afterBase = switch (scene) {
                case MOMENTS, DIARY -> "style_only";
                case VOICE_CALL -> "time_only";
                default -> "full";
            };
            String toolHint = scene.enableChatTools() && toolManager != null
                    ? toolManager.buildToolsPromptHint(turn.userId()) : "";
            return promptAssemblyPort.assembleIfEnabled(
                    new com.lianyu.service.agent.PromptAssemblyRequest(
                            turn.userId(),
                            character.getId(),
                            turn.conversationId(),
                            character.getName(),
                            character.getSettings(),
                            character.getPromptTemplate(),
                            scene.name(),
                            assembled.outputLanguage(),
                            afterBase,
                            scene.includeTime(),
                            null,
                            scene.enableChatTools(),
                            com.lianyu.service.character.CharacterPreferenceResolver
                                    .showInnerThoughts(character),
                            behaviorResolver != null
                                    ? behaviorResolver.resolve(character).maxRepliesPerTurn() : 2,
                            toolHint));
        } catch (Exception e) {
            log.warn("Agent prompt assembly threw ({}), fallback to Java", e.toString());
            return null;
        }
    }
}

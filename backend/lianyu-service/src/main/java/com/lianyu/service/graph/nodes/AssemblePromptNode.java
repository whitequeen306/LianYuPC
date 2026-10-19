package com.lianyu.service.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.ai.graph.ChatTurnState;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.agent.PromptAssemblyPort;
import com.lianyu.service.agent.PromptAssemblyRequest;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.graph.ChatTurnGroupExtras;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import com.lianyu.service.tools.ToolManager;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * prompt 组装节点：**只调 agent**，Java 侧不再保留组装回退。
 *
 * <p>OFF 退役后，组装失败即报错（而不是静默切回 Java）。这样缺能力会立刻暴露，
 * 不会出现「看起来在跑、其实走了另一条腿」的静默降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssemblePromptNode implements NodeAction {

    private final PromptAssemblyPort promptAssemblyPort;
    private final OutputLanguageService outputLanguageService;
    private final TimeTool timeTool;
    private final ToolManager toolManager;
    private final CharacterChatBehaviorResolver behaviorResolver;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        ChatTurnState turn = new ChatTurnState(state);
        Character character = (Character) turn.characterRef();
        if (character == null) {
            throw new IllegalStateException("ChatTurn missing characterRef");
        }
        ChatTurnScene scene = turn.scene();
        ChatTurnGroupExtras groupExtras =
                state.value(ChatTurnKeys.GROUP_EXTRAS, ChatTurnGroupExtras.class).orElse(null);

        String outputLanguage = outputLanguageService.resolveForRequest(
                turn.userId(), turn.rawUserText());
        String systemPrompt = assemble(turn, character, scene, groupExtras, outputLanguage);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalStateException(
                    "agent prompt assembly returned blank: scene=" + scene
                            + ", characterId=" + character.getId());
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(ChatTurnKeys.SYSTEM_PROMPT, systemPrompt);
        updates.put(ChatTurnKeys.OUTPUT_LANGUAGE, outputLanguage);
        log.info("node=assemblePrompt scene={} characterId={} promptChars={} source=agent",
                scene, character.getId(), systemPrompt.length());
        return updates;
    }

    private String assemble(ChatTurnState turn,
                            Character character,
                            ChatTurnScene scene,
                            ChatTurnGroupExtras groupExtras,
                            String outputLanguage) {
        boolean includeTime = scene.includeTime();
        String timeFact = includeTime ? timeTool.readCurrentTimeFact() : null;
        Map<String, Object> toolFlags = scene.enableChatTools()
                ? toolManager.promptHintFlags(turn.userId())
                : Map.of("chat", false);
        CharacterChatBehavior behavior = behaviorResolver.resolve(character);
        int maxPieces = groupExtras != null && groupExtras.maxPieces() > 0
                ? groupExtras.maxPieces()
                : behavior.maxRepliesPerTurn();
        boolean showInnerThoughts = scene != ChatTurnScene.VOICE_CALL
                && CharacterPreferenceResolver.showInnerThoughts(character);
        String memoryText = turn.modelUserText() == null || turn.modelUserText().isBlank()
                ? turn.rawUserText()
                : turn.modelUserText();

        PromptAssemblyRequest request = new PromptAssemblyRequest(
                turn.userId(),
                character.getId(),
                turn.conversationId(),
                character.getName(),
                character.getSettings(),
                character.getPromptTemplate(),
                scene.name(),
                outputLanguage,
                afterBaseOf(scene),
                includeTime,
                timeFact,
                scene.enableChatTools(),
                showInnerThoughts,
                maxPieces,
                "",
                toolFlags,
                memoryText,
                turn.extraSystemSuffix(),
                groupExtras == null ? 0 : groupExtras.maxPieces(),
                groupExtras == null ? "" : groupExtras.otherCharactersLine(),
                groupExtras == null ? "" : groupExtras.mentionCtx());
        try {
            return promptAssemblyPort.assembleIfEnabled(request);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "agent prompt assembly failed: scene=" + scene
                            + ", characterId=" + character.getId(), e);
        }
    }

    /** 与 Java 原 assemble() 的场景分支逐一对应：GROUP 不属于 full。 */
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
}

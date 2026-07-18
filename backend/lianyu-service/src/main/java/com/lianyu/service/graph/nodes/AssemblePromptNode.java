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

        Map<String, Object> updates = new HashMap<>();
        updates.put(ChatTurnKeys.SYSTEM_PROMPT, assembled.systemPrompt());
        updates.put(ChatTurnKeys.MEMORY_BLOCK, assembled.memoryBlock());
        updates.put(ChatTurnKeys.RELATIONSHIP_BLOCK, assembled.relationshipBlock());
        updates.put(ChatTurnKeys.SESSION_SUMMARY_BLOCK, assembled.sessionSummaryBlock());
        updates.put(ChatTurnKeys.OUTPUT_LANGUAGE, assembled.outputLanguage());
        log.info("node=assemblePrompt scene={} characterId={} promptChars={}",
                scene, character.getId(), assembled.systemPrompt().length());
        return updates;
    }
}

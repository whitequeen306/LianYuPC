package com.lianyu.service.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.lianyu.ai.graph.ChatTurnKeys;
import com.lianyu.ai.graph.ChatTurnState;
import com.lianyu.dao.entity.Message;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.graph.ChatTurnMessageAssembler;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssembleMessagesNode implements NodeAction {

    private final ChatTurnMessageAssembler messageAssembler;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        ChatTurnState turn = new ChatTurnState(state);
        List<Message> history = state.value(ChatTurnKeys.HISTORY_MESSAGES, List.class).orElse(List.of());
        List<MessageDto> prepared = state.value(ChatTurnKeys.PREPARED_MESSAGES, List.class).orElse(null);
        Long currentUserMsgId = state.value(ChatTurnKeys.CURRENT_USER_MSG_ID, Long.class).orElse(null);

        List<MessageDto> messages = messageAssembler.assemble(
                turn.systemPrompt(),
                history,
                currentUserMsgId,
                turn.modelUserText(),
                prepared);

        log.info("node=assembleMessages messageCount={}", messages.size());
        return ChatTurnState.put(ChatTurnKeys.MESSAGE_DTOS, messages);
    }
}

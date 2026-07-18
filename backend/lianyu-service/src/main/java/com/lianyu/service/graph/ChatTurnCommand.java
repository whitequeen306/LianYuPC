package com.lianyu.service.graph;

import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.ai.graph.StreamTokenConsumer;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Message;
import com.lianyu.service.dto.MessageDto;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * 一次 ChatTurn Graph 执行的输入命令（外层适配器构造，不持有 SSE）。
 */
@Value
@Builder
public class ChatTurnCommand {
    ChatTurnScene scene;
    Long userId;
    Long conversationId;
    Character character;
    String provider;
    String model;
    Double temperature;
    String rawUserText;
    String modelUserText;
    String imageUrl;
    Long currentUserMsgId;
    List<Message> historyMessages;
    /** 若非空则跳过内部 history→MessageDto 转换，直接使用。 */
    List<MessageDto> preparedMessages;
    String extraSystemSuffix;
    ChatTurnPromptAssembler.GroupExtras groupExtras;
    boolean streaming;
    StreamTokenConsumer streamSink;
    Map<String, Object> toolCharacterSettings;
}

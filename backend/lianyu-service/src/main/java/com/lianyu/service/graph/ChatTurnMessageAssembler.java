package com.lianyu.service.graph;

import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Message;
import com.lianyu.service.dto.MessageDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the message list for the main chat model from system prompt + history.
 */
@Component
public class ChatTurnMessageAssembler {

    public List<MessageDto> assemble(
            String systemPrompt,
            List<Message> history,
            Long currentUserMsgId,
            String currentAiUserContent,
            List<MessageDto> preparedMessages
    ) {
        if (preparedMessages != null && !preparedMessages.isEmpty()) {
            List<MessageDto> copy = new ArrayList<>(preparedMessages.size());
            for (MessageDto dto : preparedMessages) {
                MessageDto next = new MessageDto();
                next.setRole(dto.getRole());
                next.setContent(ImageMessageHistoryText.forHistory(dto.getContent(),
                        dto.getImageUrl() != null && !dto.getImageUrl().isBlank()));
                copy.add(next);
            }
            if (!copy.isEmpty() && "system".equalsIgnoreCase(copy.get(0).getRole())) {
                copy.get(0).setContent(systemPrompt);
            } else {
                MessageDto system = new MessageDto();
                system.setRole("system");
                system.setContent(systemPrompt);
                copy.add(0, system);
            }
            return copy;
        }

        List<MessageDto> allMessages = new ArrayList<>();
        MessageDto system = new MessageDto();
        system.setRole("system");
        system.setContent(systemPrompt);
        allMessages.add(system);

        if (history == null) {
            return allMessages;
        }
        for (Message msg : history) {
            // 通话逐轮文本只服务「本通」；结束后仅摘要可进文字聊上下文，turn 不再喂模型
            if (isVoiceCallTurnMessage(msg)) {
                continue;
            }
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole() == null ? "user" : msg.getRole().toLowerCase());
            boolean hasImage = msg.getImageUrl() != null && !msg.getImageUrl().isBlank();
            String content = MessageModelContent.forModel(msg);
            boolean isCurrent = currentUserMsgId != null && currentUserMsgId.equals(msg.getId());
            if (isCurrent && currentAiUserContent != null && !currentAiUserContent.isBlank()) {
                content = currentAiUserContent.contains("<user_message")
                        ? currentAiUserContent
                        : UserInputSanitizer.wrapStoredTextForModel(currentAiUserContent);
                if (hasImage && isEmptyUserMessageXml(content)) {
                    content = UserInputSanitizer.wrapStoredTextForModel("用户发送了一张图片");
                }
            } else if (hasImage) {
                // 优先 contextContent（识图描述），再归一成历史占位；勿只用可见 content 丢掉描述
                content = ImageMessageHistoryText.forHistory(content, true);
            } else if (content == null || content.isBlank()) {
                continue;
            }
            dto.setContent(content);
            allMessages.add(dto);
        }
        return allMessages;
    }

    private static boolean isVoiceCallTurnMessage(Message msg) {
        if (msg == null || msg.getAudioUrl() == null) {
            return false;
        }
        return "system/voice-call-turn".equals(msg.getAudioUrl().trim());
    }

    private static boolean isEmptyUserMessageXml(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String inner = content.replaceAll("(?s)<user_message[^>]*>|</user_message>", "").trim();
        return inner.isEmpty();
    }
}

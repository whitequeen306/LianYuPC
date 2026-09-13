package com.lianyu.service.ai;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * 视觉消息（inline base64 图片 user message）构建器。
 * 同时被 AiChatService#toSpringMessages（历史里带图的消息）与 VisionChatService（识图链路）复用，
 * 独立成组件避免两条链路互相依赖。
 */
@Component
@RequiredArgsConstructor
public class VisionMessageBuilder {

    /** Stage-1 VL user hint: neutral, no roleplay (roleplay/placeholder text can trip content filters). */
    public static final String VISION_ANALYSIS_USER_HINT = "请客观描述这张图片中可见的内容。";

    private final FileStorageService fileStorageService;

    /** Stage-1 识图：中立提示，避免把角色扮演/占位文案送进 VL（易触发内容审核）。 */
    public Message buildVisionAnalysisUserMessage(MessageDto dto) {
        MessageDto analysisDto = new MessageDto();
        analysisDto.setRole(dto.getRole());
        analysisDto.setImageUrl(dto.getImageUrl());
        String raw = dto.getContent();
        if (raw == null || raw.isBlank() || isImagePlaceholderContent(raw)) {
            analysisDto.setContent(VISION_ANALYSIS_USER_HINT);
        } else {
            String caption = raw.replaceAll("(?s)<user_message[^>]*>|</user_message>", "").trim();
            if (caption.isBlank() || isImagePlaceholderContent(caption)) {
                analysisDto.setContent(VISION_ANALYSIS_USER_HINT);
            } else {
                String shortCaption = caption.length() > 200 ? caption.substring(0, 200) : caption;
                analysisDto.setContent("用户附言：" + shortCaption + "\n" + VISION_ANALYSIS_USER_HINT);
            }
        }
        return buildVisionUserMessage(analysisDto);
    }

    public static boolean isImagePlaceholderContent(String text) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        return t.isEmpty()
                || t.contains("用户发送了一张图片")
                || t.contains("用你的性格自然回应");
    }

    public Message buildVisionUserMessage(MessageDto dto) {
        String objectKey = FileStorageService.extractObjectKey(dto.getImageUrl());
        if (objectKey == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的图片地址");
        }
        byte[] bytes = fileStorageService.readObjectBytes(objectKey);
        String contentType = fileStorageService.resolveContentType(objectKey);
        String text = dto.getContent() != null && !dto.getContent().isBlank()
                ? dto.getContent()
                : VISION_ANALYSIS_USER_HINT;
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(contentType))
                .data(new ByteArrayResource(bytes))
                .build();
        return UserMessage.builder()
                .text(text)
                .media(media)
                .build();
    }
}

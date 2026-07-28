package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VoiceCallTurnResponse {
    private String userText;
    private String replyText;
    private String audioBase64;
    private String audioMimeType;
    private Long userMessageId;
    private Long replyMessageId;
}

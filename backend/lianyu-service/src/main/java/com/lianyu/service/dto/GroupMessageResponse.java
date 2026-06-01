package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GroupMessageResponse {
    private String type;
    private Long conversationId;
    private Long characterId;
    private String characterName;
    private String content;
    private String stickerUrl;
}

package com.lianyu.service.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConversationResponse {
    private Long id;
    private Long userId;
    private Long characterId;
    private String characterName;
    private String characterAvatarUrl;
    private String characterAvatarThumbUrl;
    private String mode;
    private String title;
    private String lastMessage;
    private String lastCharacterMessage;
    private LocalDateTime createdAt;
}

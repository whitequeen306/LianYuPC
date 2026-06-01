package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class ConversationResponse {
    private Long id;
    private Long userId;
    private Long characterId;
    private String characterName;
    private String characterAvatarUrl;
    private String mode;
    private String title;
    private String lastMessage;
    private LocalDateTime createdAt;
}

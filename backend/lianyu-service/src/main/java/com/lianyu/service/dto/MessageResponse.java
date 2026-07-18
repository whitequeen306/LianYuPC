package com.lianyu.service.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageResponse {
    private Long id;
    private Long seq;
    private Long conversationId;
    private String role;
    private Long characterId;
    private String content;
    private String imageUrl;
    private String audioUrl;
    private Integer tokens;
    private LocalDateTime createdAt;
}

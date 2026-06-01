package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

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
    private Integer tokens;
    private LocalDateTime createdAt;
}

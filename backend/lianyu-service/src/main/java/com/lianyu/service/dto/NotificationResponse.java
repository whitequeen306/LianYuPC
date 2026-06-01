package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationResponse {
    private Long id;
    private String type;
    private Long conversationId;
    private Long characterId;
    private String title;
    private String contentPreview;
    private Boolean read;
    private LocalDateTime createdAt;
}

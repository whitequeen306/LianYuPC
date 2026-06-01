package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class MomentPostResponse {
    private Long id;
    private Long characterId;
    private String characterName;
    private String characterAvatarUrl;
    private Long conversationId;
    private String content;
    private String postType;
    private Map<String, Object> metaJson;
    private LocalDateTime createdAt;
}

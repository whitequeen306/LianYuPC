package com.lianyu.service.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MomentPostResponse {
    private Long id;
    /** CHARACTER | USER */
    private String authorType;
    private Long characterId;
    private String characterName;
    private String characterAvatarUrl;
    private String userDisplayName;
    private String imageUrl;
    private Long conversationId;
    private String content;
    private String postType;
    private Map<String, Object> metaJson;
    private LocalDateTime createdAt;
}

package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MomentCommentResponse {
    private Long id;
    private Long postId;
    private String authorType;
    private Long characterId;
    private String characterName;
    private String characterAvatarUrl;
    private String userDisplayName;
    private Long parentId;
    private Long rootId;
    private String content;
    private String sourceType;
    private LocalDateTime createdAt;
}

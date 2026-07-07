package com.lianyu.service.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MomentCommentResponse {
    private Long id;
    private Long postId;
    private String authorType;
    private Long characterId;
    private String characterName;
    private String characterAvatarUrl;
    private String characterAvatarThumbUrl;
    private String userDisplayName;
    private Long parentId;
    private Long rootId;
    private String content;
    private String sourceType;
    private LocalDateTime createdAt;
}

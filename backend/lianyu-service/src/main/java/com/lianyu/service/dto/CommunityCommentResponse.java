package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CommunityCommentResponse {
    private Long id;
    private Long postId;
    private Long authorUserId;
    private String nickname;
    private String avatarUrl;
    private String content;
    private String status;
    private LocalDateTime createdAt;
}

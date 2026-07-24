package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CommunityPostResponse {
    private Long id;
    private Long authorUserId;
    private String nickname;
    private String avatarUrl;
    private String content;
    private List<String> imageUrls;
    private Long linkedCharacterId;
    private String linkedCharacterName;
    /** Original linked-character avatar (public URL). */
    private String linkedCharacterAvatarUrl;
    /** Thumb when available; may equal linkedCharacterAvatarUrl for user uploads. */
    private String linkedCharacterAvatarThumbUrl;
    private String status;
    private String rejectReason;
    private int likeCount;
    private int commentCount;
    private boolean likedByMe;
    private LocalDateTime createdAt;
}

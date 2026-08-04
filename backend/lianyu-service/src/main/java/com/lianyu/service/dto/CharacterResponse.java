package com.lianyu.service.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CharacterResponse {
    private Long id;
    private Long ownerUserId;
    private String name;
    private String avatarUrl;
    /** 广场角色头像缩略图（square-avatars-thumb/），列表展示优先使用 */
    private String avatarThumbUrl;
    private Map<String, Object> settings;
    private String promptTemplate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** Square slug with VC voice mapping, e.g. raiden; null when unavailable. */
    private String voicePetId;
    /** True when this character has a READY user-custom voice (isolated from official pets). */
    private Boolean customVoiceReady;
}

package com.lianyu.service.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** 角色广场列表卡片（不含 promptTemplate） */
@Data
@Builder
public class CharacterSquareTemplateCardResponse {
    private Long id;
    private String slug;
    private String name;
    private String summary;
    /** 296×296 缩略图，列表首屏使用 */
    private String avatarThumbUrl;
    /** 原图，缩略图不可用时回退 */
    private String avatarUrl;
    private List<String> tags;
    private boolean added;
    private Long addedCharacterId;
    private long likeCount;
    private boolean liked;
    /** 广场 VC 角色：固定开场/时段语音等 */
    private boolean hasVoiceInteraction;
    /** 累计被用户添加次数 */
    private long addCount;
}

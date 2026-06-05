package com.lianyu.service.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CharacterSquareTemplateResponse {
    private Long id;
    private String slug;
    private String name;
    private String summary;
    private String avatarUrl;
    private String promptTemplate;
    private List<String> tags;
    private List<String> tagKeys;
    /** 当前用户是否已加入 */
    private boolean added;
    /** 已加入时对应的个人角色 ID */
    private Long addedCharacterId;
    /** 点赞数 */
    private long likeCount;
    /** 当前用户是否已点赞 */
    private boolean liked;
}

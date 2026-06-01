package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

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
}

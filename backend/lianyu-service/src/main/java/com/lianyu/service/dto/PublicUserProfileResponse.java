package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PublicUserProfileResponse {
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private boolean showCharactersOnProfile;
    private boolean charactersHidden;
    private List<PublicCharacterCard> characters;
    /** True when the viewer is looking at their own profile. */
    private boolean self;
}

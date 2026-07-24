package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PublicCharacterCard {
    private Long characterId;
    private String name;
    private String avatarUrl;
    /** Square-avatar thumb when available; same as avatarUrl for user uploads. */
    private String avatarThumbUrl;
    private int companionshipDays;
}

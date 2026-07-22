package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PublicCharacterCard {
    private Long characterId;
    private String name;
    private String avatarUrl;
    private int companionshipDays;
}

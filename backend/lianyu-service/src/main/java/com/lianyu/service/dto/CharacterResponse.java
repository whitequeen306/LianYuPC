package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class CharacterResponse {
    private Long id;
    private Long ownerUserId;
    private String name;
    private String avatarUrl;
    private Map<String, Object> settings;
    private String promptTemplate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

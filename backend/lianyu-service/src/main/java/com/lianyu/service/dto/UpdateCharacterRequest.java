package com.lianyu.service.dto;

import lombok.Data;
import java.util.Map;

@Data
public class UpdateCharacterRequest {
    private String name;
    private String avatarUrl;
    private Map<String, Object> settings;
    private String promptTemplate;
}

package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GenerateCharacterRequest {
    private String provider;

    @NotBlank(message = "请输入动漫角色名称或描述")
    private String description;
}
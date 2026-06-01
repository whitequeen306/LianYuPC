package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateConversationRequest {
    @NotNull
    private Long characterId;
    @NotBlank
    private String mode;
    private String title;
}

package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;

@Data
public class AiChatRequest {
    @NotBlank
    private String provider;

    private String model;
    private Double temperature;

    @NotEmpty
    private List<MessageDto> messages;
}

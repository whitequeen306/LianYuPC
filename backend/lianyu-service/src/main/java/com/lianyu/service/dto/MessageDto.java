package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MessageDto {
    @NotBlank
    private String role;

    private String content;

    /** 仅当前轮用户消息携带；历史消息不传图（单轮视觉识别） */
    private String imageUrl;
}

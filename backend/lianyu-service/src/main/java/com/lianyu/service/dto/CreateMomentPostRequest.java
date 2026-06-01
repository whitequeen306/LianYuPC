package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateMomentPostRequest {
    @NotBlank(message = "动态内容不能为空")
    @Size(max = 512, message = "动态内容不能超过 512 字")
    private String content;
}

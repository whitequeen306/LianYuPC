package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SquareCommentRequest {
    @NotBlank(message = "评语不能为空")
    @Size(max = 60, message = "评语最多 60 字")
    private String content;
}

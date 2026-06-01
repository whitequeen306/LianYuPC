package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateMomentCommentRequest {
    @NotBlank
    @Size(max = 500)
    private String content;

    private Long parentId;
}

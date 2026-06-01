package com.lianyu.service.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateGroupTitleRequest {
    @Size(max = 64, message = "群聊名称最多 64 个字符")
    private String title;
}

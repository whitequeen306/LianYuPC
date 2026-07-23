package com.lianyu.service.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateCommunityPostRequest {
    @Size(max = 1000, message = "动态内容不能超过 1000 字")
    private String content;

    @Size(max = 9, message = "最多上传 9 张图片")
    private List<@Size(max = 512) String> imageUrls;

    private Long linkedCharacterId;
}

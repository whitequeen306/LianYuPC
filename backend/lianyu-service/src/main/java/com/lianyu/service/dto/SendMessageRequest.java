package com.lianyu.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendMessageRequest {
    @NotBlank
    private String provider;
    private String model;
    private Double temperature;
    private String content;
    /** 已通过上传接口获得的公开图片 URL */
    private String imageUrl;

    /** 服务端填充：发给模型的 XML 包裹正文，客户端不可设置 */
    @JsonIgnore
    private String modelContentForAi;

    @AssertTrue(message = "消息内容或图片至少填一项")
    public boolean isContentOrImagePresent() {
        boolean hasContent = content != null && !content.isBlank();
        boolean hasImage = imageUrl != null && !imageUrl.isBlank();
        return hasContent || hasImage;
    }
}

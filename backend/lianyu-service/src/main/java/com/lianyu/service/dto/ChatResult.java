package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatResult {
    private String content;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    /** 图片识图阶段的客观描述，供落库历史占位使用 */
    private String imageDescription;
    /** 只要正文的结果（agent 接管时用，token 统计暂缺）。 */
    public static ChatResult ofContent(String content) {
        ChatResult result = new ChatResult(content, null, null, null, null);
        return result;
    }
}
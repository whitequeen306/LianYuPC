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
}

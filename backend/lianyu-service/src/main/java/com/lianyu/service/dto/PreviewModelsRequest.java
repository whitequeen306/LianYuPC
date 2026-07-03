package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 未保存配置时预览模型列表的请求体。
 * baseUrl 必填；apiKey 对 Ollama 可留空。
 */
@Data
public class PreviewModelsRequest {

    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    /** OpenAI 兼容接口必填；Ollama 可留空 */
    private String apiKey;
}

package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateVaultRequest {
    /** 可选别名，留空则由服务端生成 Provider{id} */
    private String provider;

    /** OpenAI 兼容接口必填；Ollama 可留空 */
    private String apiKey;

    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    @NotBlank(message = "默认模型不能为空")
    private String modelDefault;

    /** 可选：该 Provider 的默认识图/多模态模型；空则走平台 qwen3-vl-plus */
    private String visionModelDefault;

    /** 备注（可选） */
    private String remark;
}

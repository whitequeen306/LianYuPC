package com.lianyu.service.dto;

import lombok.Data;

@Data
public class UpdateVaultRequest {
    private String apiKey;
    private String baseUrl;
    private String modelDefault;
    /** 可选识图模型；传空字符串可清空 */
    private String visionModelDefault;
    private Integer enabled;
    private String remark;
}

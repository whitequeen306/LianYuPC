package com.lianyu.service.dto;

import lombok.Data;

@Data
public class UpdateVaultRequest {
    private String apiKey;
    private String baseUrl;
    private String modelDefault;
    /** @deprecated 识图固定走平台多模态；字段保留兼容，值不再生效 */
    private String visionModelDefault;
    private Integer enabled;
    private String remark;
}

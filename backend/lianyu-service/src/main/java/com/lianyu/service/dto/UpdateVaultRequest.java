package com.lianyu.service.dto;

import lombok.Data;

@Data
public class UpdateVaultRequest {
    private String apiKey;
    private String baseUrl;
    private String modelDefault;
    private Integer enabled;
    private String remark;
}

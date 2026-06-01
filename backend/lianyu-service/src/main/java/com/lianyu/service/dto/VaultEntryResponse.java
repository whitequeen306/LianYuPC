package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VaultEntryResponse {
    private Long id;
    private Long userId;
    /** USER / DEFAULT */
    private String vaultScope;
    private String provider;
    /** API Key，仅在 create 时返回明文；list/get/update 返回脱敏值（如 sk-****...abcd） */
    private String apiKey;
    private String baseUrl;
    private String modelDefault;
    private Integer enabled;
    private String remark;
    private String keyVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
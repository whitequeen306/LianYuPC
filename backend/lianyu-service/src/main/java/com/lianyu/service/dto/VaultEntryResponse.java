package com.lianyu.service.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    /** text（默认）| vision */
    private String purpose;
    /** @deprecated 识图走 purpose=vision 的 vault 或平台默认；保留兼容 */
    private String visionModelDefault;
    private Integer enabled;
    private String remark;
    private String keyVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

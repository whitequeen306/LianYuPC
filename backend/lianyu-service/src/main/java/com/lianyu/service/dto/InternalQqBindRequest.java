package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 网关 → 后端：用户在 QQ 里发了个绑定码。 */
@Data
public class InternalQqBindRequest {
    @NotBlank
    @Size(max = 32)
    private String channel;
    @NotBlank
    @Size(max = 64)
    private String externalId;
    @NotBlank
    @Size(max = 16)
    private String code;
}

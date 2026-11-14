package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 网关 → 后端：一条 QQ 消息要跑成一次回合。 */
@Data
public class InternalQqTurnRequest {
    @NotBlank
    @Size(max = 32)
    private String channel;
    @NotBlank
    @Size(max = 64)
    private String externalId;
    @NotBlank
    @Size(max = 4000)
    private String text;
}

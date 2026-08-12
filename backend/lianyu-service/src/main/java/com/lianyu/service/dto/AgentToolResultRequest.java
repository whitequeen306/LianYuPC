package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 桌面客户端回传本地工具执行结果。
 */
@Data
public class AgentToolResultRequest {

    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    /** 执行是否成功 */
    private Boolean ok;

    /** 成功时的文本输出 */
    private String content;

    /** 失败时的错误说明 */
    private String error;
}

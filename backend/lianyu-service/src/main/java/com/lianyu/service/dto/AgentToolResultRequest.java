package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 桌面客户端回传一次能力执行结果。
 *
 * <p>`requestId` 即 Python 生成的幂等 `stepId`；`images` 只给视觉分析用，
 * 不进文本上下文，因此不参与字符消毒与 8000 字截断（只校验 mime 与大小）。
 */
@Data
public class AgentToolResultRequest {

    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    /** 执行是否成功 */
    private Boolean ok;

    /** 成功时的文本输出 */
    private String content;

    /** 失败时的错误码（如 window_not_found）；旧客户端可能是人话，统一按错误码透传 */
    private String error;

    /** 截图帧：[{mime,b64,w,h,scaleFactor}]，最多 2 帧 */
    private List<Map<String, Object>> images;

    /** 客户端策略结论：[{level,confirmed,reason}] */
    private Map<String, Object> policy;
}

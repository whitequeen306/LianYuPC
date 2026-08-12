package com.lianyu.service.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.Data;

/**
 * 桌面客户端注册本地 MCP 工具清单（整体替换式）。
 */
@Data
public class RegisterAgentToolsRequest {

    private List<AgentToolSpec> tools;

    @Data
    public static class AgentToolSpec {
        /** 工具名（字母/数字/下划线/中划线） */
        private String name;
        /** 工具描述（供模型路由决策） */
        private String description;
        /** JSON Schema（对象）；null 视为无参数 */
        private JsonNode inputSchema;
        /** 危险操作标记（客户端执行前会弹确认；此处仅作记录） */
        private Boolean dangerous;
    }
}

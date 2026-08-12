package com.lianyu.service.tools.bridge;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把桌面客户端注册的本地 MCP 工具包装成 Spring AI {@link ToolCallback}：
 * 模型发起调用时经 {@link AgentBridgeService#dispatch} 下发到客户端本地执行。
 */
public class ClientBridgeToolCallback implements ToolCallback {

    private final AgentBridgeService bridgeService;
    private final Long userId;
    private final AgentBridgeService.ClientTool tool;

    public ClientBridgeToolCallback(AgentBridgeService bridgeService, Long userId,
                                    AgentBridgeService.ClientTool tool) {
        this.bridgeService = bridgeService;
        this.userId = userId;
        this.tool = tool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        return bridgeService.dispatch(userId, tool.name(), toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}

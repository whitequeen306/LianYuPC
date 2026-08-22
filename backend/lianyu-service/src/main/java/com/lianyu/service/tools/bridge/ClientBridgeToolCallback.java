package com.lianyu.service.tools.bridge;

import com.lianyu.service.tools.ChatToolContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把桌面客户端注册的本地 MCP 工具包装成 Spring AI {@link ToolCallback}：
 * 模型发起调用时经 {@link AgentBridgeService#dispatch} 下发到客户端本地执行。
 *
 * <p>角色名/头像在构造时从 {@link ChatToolContext} 快照：流式工具执行会换线程，
 * 不能在 {@link #call} 时再读 ThreadLocal。
 */
public class ClientBridgeToolCallback implements ToolCallback {

    private final AgentBridgeService bridgeService;
    private final Long userId;
    private final AgentBridgeService.ClientTool tool;
    private final Long characterId;
    private final String characterName;
    private final String characterAvatarUrl;

    public ClientBridgeToolCallback(AgentBridgeService bridgeService, Long userId,
                                    AgentBridgeService.ClientTool tool) {
        this(bridgeService, userId, tool, ChatToolContext.current());
    }

    public ClientBridgeToolCallback(AgentBridgeService bridgeService, Long userId,
                                    AgentBridgeService.ClientTool tool, ChatToolContext.Scope scope) {
        this.bridgeService = bridgeService;
        this.userId = userId;
        this.tool = tool;
        this.characterId = scope != null ? scope.characterId() : null;
        this.characterName = scope != null ? scope.characterName() : null;
        this.characterAvatarUrl = scope != null ? scope.characterAvatarUrl() : null;
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
        return bridgeService.dispatch(userId, tool.name(), toolInput,
                characterId, characterName, characterAvatarUrl);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}

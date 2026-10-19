package com.lianyu.service.tools;

import cn.hutool.core.collection.CollUtil;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.tools.bridge.AgentBridgeService;
import com.lianyu.service.tools.bridge.ClientBridgeToolCallback;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一注册角色对话可用的 Spring AI Tool（时间、天气、长期记忆、角色近况等），
 * 并在用户桌面端工具桥在线时合并其本地 MCP 工具（经 {@link AgentBridgeService} 远程执行）。
 */
@Component
@RequiredArgsConstructor
public class ToolManager {

    private final TimeTool timeTool;
    private final WeatherTool weatherTool;
    private final RecentActivityTool recentActivityTool;
    private final WriteDiaryTool writeDiaryTool;
    private final AgentBridgeService agentBridgeService;

    @Value("${lianyu.tools.chat.enabled:true}")
    private boolean chatToolsEnabled;

    @Value("${lianyu.tools.weather.enabled:true}")
    private boolean weatherEnabled;

    @Value("${lianyu.memory.agentic.enabled:true}")
    private boolean memoryAgenticEnabled;

    @Value("${lianyu.tools.recent-activity.enabled:true}")
    private boolean recentActivityEnabled;

    @Value("${lianyu.tools.write-diary.enabled:true}")
    private boolean writeDiaryEnabled;

    /**
     * 为本次 ChatModel 调用解析 Tool 列表；须已设置 {@link ChatToolContext}。
     * 服务端内置工具之外，若当前用户的桌面工具桥在线，则追加其本地工具
     * （同名时服务端工具优先，本地工具跳过）。
     */
    public List<ToolCallback> resolveToolCallbacks(AiChatRequest request) {
        if (!chatToolsEnabled || request == null || request.getChatToolCharacterId() == null
                || request.isDisableChatTools()) {
            return List.of();
        }
        List<Object> providers = CollUtil.newArrayList(timeTool);
        if (weatherEnabled) {
            providers.add(weatherTool);
        }
        if (recentActivityEnabled) {
            providers.add(recentActivityTool);
        }
        if (writeDiaryEnabled) {
            providers.add(writeDiaryTool);
        }
        List<ToolCallback> callbacks = new ArrayList<>(List.of(ToolCallbacks.from(providers.toArray())));
        appendClientBridgeTools(callbacks);
        return List.copyOf(callbacks);
    }

    private void appendClientBridgeTools(List<ToolCallback> callbacks) {
        ChatToolContext.Scope scope = ChatToolContext.current();
        if (scope == null || scope.userId() == null) {
            return;
        }
        List<AgentBridgeService.ClientTool> clientTools = agentBridgeService.availableTools(scope.userId());
        if (clientTools.isEmpty()) {
            return;
        }
        Set<String> taken = new HashSet<>();
        for (ToolCallback callback : callbacks) {
            taken.add(callback.getToolDefinition().name());
        }
        for (AgentBridgeService.ClientTool tool : clientTools) {
            if (taken.add(tool.name())) {
                callbacks.add(new ClientBridgeToolCallback(agentBridgeService, scope.userId(), tool, scope));
            }
        }
    }

    boolean hasComputerTask(Long userId) {
        if (userId == null) {
            return false;
        }
        return agentBridgeService.availableTools(userId).stream()
                .anyMatch(t -> "computer_task".equals(t.name()));
    }
    /**
     * 工具提示的开关事实（agent 侧据此生成文案，Java 不再拼接文本）。
     *
     * <p>`computer` 依赖桌面桥接的实时会话（进程内内存态），只能由 Java 提供。
     */
    public java.util.Map<String, Object> promptHintFlags(Long userId) {
        java.util.Map<String, Object> flags = new java.util.LinkedHashMap<>();
        flags.put("chat", chatToolsEnabled);
        flags.put("memory", memoryAgenticEnabled);
        flags.put("recent", recentActivityEnabled);
        flags.put("diary", writeDiaryEnabled);
        flags.put("computer", hasComputerTask(userId));
        return flags;
    }
}
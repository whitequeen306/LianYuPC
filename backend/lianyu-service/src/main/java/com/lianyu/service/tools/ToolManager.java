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
    private final MemorySearchTool memorySearchTool;
    private final RecentActivityTool recentActivityTool;
    private final AgentBridgeService agentBridgeService;

    @Value("${lianyu.tools.chat.enabled:true}")
    private boolean chatToolsEnabled;

    @Value("${lianyu.tools.weather.enabled:true}")
    private boolean weatherEnabled;

    @Value("${lianyu.memory.agentic.enabled:true}")
    private boolean memoryAgenticEnabled;

    @Value("${lianyu.tools.recent-activity.enabled:true}")
    private boolean recentActivityEnabled;

    /**
     * 为本次 ChatModel 调用解析 Tool 列表；须已设置 {@link ChatToolContext}。
     * 服务端内置工具之外，若当前用户的桌面工具桥在线，则追加其本地工具
     * （同名时服务端工具优先，本地工具跳过）。
     */
    public List<ToolCallback> resolveToolCallbacks(AiChatRequest request) {
        if (!chatToolsEnabled || request == null || request.getChatToolCharacterId() == null) {
            return List.of();
        }
        List<Object> providers = CollUtil.newArrayList(timeTool);
        if (weatherEnabled) {
            providers.add(weatherTool);
        }
        if (memoryAgenticEnabled) {
            providers.add(memorySearchTool);
        }
        if (recentActivityEnabled) {
            providers.add(recentActivityTool);
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

    public String buildToolsPromptHint() {
        ChatToolContext.Scope scope = ChatToolContext.current();
        return buildToolsPromptHint(scope != null ? scope.userId() : null);
    }

    /**
     * @param userId 当前用户；用来判断桌面桥是否在线。组装系统提示时 ThreadLocal 还没设，
     *               必须把 ChatTurn 的 userId 传进来，否则 computer_task 规则写不进 prompt。
     */
    public String buildToolsPromptHint(Long userId) {
        if (!chatToolsEnabled) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""
                
                === 可用工具（按需调用，勿编造） ===
                - get_current_time：用户问现在几点、今天日期、星期几等时间问题时调用。
                - get_weather：用户问天气、气温、穿衣、是否带伞时调用；city 参数可省略（将使用角色设定城市）。""");
        if (memoryAgenticEnabled) {
            sb.append("""
                    - memory_search：需要回忆具体往事、过往对话片段时调用；query 用简短中文。寒暄或结构化资料已足够时不要调用。""");
        }
        if (recentActivityEnabled) {
            sb.append("""
                    - get_my_recent_life：用户问你最近在干嘛、日记/动态写了什么、近况如何时调用；寒暄勿调。""");
        }
        if (hasComputerTask(userId)) {
            sb.append("""
                    - computer_task：用户要操作【本机电脑】时本轮必须调用（打开应用、网易云搜歌/换歌/播放、整理文件、终端命令等）。
                      禁止只回复「我去换」「稍等」「让助手去搜」而不调用。上一轮已经操作过电脑、用户改口换任务，也要立刻再调，不要等对方催促。
                      参数用 instruction 写一句具体任务。闲聊、纯查资料不要调用。""");
            sb.append("\n时间/天气/记忆类工具按需调用，避免每条寒暄都调。电脑操作只要用户开口要做，本轮就必须调 computer_task。");
        } else {
            sb.append("\n仅在与用户问题相关时调用工具，避免每条寒暄都调。");
        }
        return sb.toString();
    }

    boolean hasComputerTask(Long userId) {
        if (userId == null) {
            return false;
        }
        return agentBridgeService.availableTools(userId).stream()
                .anyMatch(t -> "computer_task".equals(t.name()));
    }
}

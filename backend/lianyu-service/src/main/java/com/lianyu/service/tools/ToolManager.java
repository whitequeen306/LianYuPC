package com.lianyu.service.tools;

import cn.hutool.core.collection.CollUtil;
import com.lianyu.service.dto.AiChatRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一注册角色对话可用的 Spring AI Tool（时间、天气、长期记忆、角色近况等）。
 */
@Component
@RequiredArgsConstructor
public class ToolManager {

    private final TimeTool timeTool;
    private final WeatherTool weatherTool;
    private final MemorySearchTool memorySearchTool;
    private final RecentActivityTool recentActivityTool;

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
        return List.of(ToolCallbacks.from(providers.toArray()));
    }

    public String buildToolsPromptHint() {
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
        sb.append("\n仅在与用户问题相关时调用工具，避免每条消息都调用。");
        return sb.toString();
    }
}

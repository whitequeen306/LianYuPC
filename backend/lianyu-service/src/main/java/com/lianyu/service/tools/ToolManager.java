package com.lianyu.service.tools;

import com.lianyu.dao.entity.Character;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具开关事实（agent 侧据此生成提示文案，Java **不拼接任何 prompt 文本**）。
 *
 * <p>能力层下线后（2026-10-25）这里只剩「聊天工具开关」这一件事：
 * 时间 / 天气 / 记忆 / 近况 / 写日记，全部由配置决定，与客户端在线状态无关。
 *
 * 桌面能力层已删除，Java 侧不再有任何「桌面能力在线」的判断来源。
 */
@Component
@RequiredArgsConstructor
public class ToolManager {

    @Value("${lianyu.tools.chat.enabled:true}")
    private boolean chatToolsEnabled;

    @Value("${lianyu.memory.agentic.enabled:true}")
    private boolean memoryAgenticEnabled;

    @Value("${lianyu.tools.recent-activity.enabled:true}")
    private boolean recentActivityEnabled;

    @Value("${lianyu.tools.write-diary.enabled:true}")
    private boolean writeDiaryEnabled;

    /** 工具提示的开关事实（无角色上下文的场景）。 */
    public Map<String, Object> promptHintFlags(Long userId) {
        return promptHintFlags(userId, null);
    }

    /**
     * 带角色上下文的开关事实。
     *
     * 模型也拿不到任何桌面工具。保留键名只是为了不改 Python 侧的提示词键序契约。
     */
    public Map<String, Object> promptHintFlags(Long userId, Character character) {
        Map<String, Object> flags = new java.util.LinkedHashMap<>();
        flags.put("chat", chatToolsEnabled);
        flags.put("memory", memoryAgenticEnabled);
        flags.put("recent", recentActivityEnabled);
        flags.put("diary", writeDiaryEnabled);
        return flags;
    }
}

package com.lianyu.service.tools;

import com.lianyu.dao.entity.Character;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.tools.bridge.AgentBridgeService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具开关事实（agent 侧据此生成提示文案，Java **不拼接任何 prompt 文本**）。
 *
 * <p>能力层重构后这里只剩一件事：把「哪些能力开关是开的」告诉 Python。
 * 原先的 `resolveToolCallbacks` / `appendClientBridgeTools`（Spring AI ToolCallback 装配）
 * 已随外借引擎一起删除 —— 现在 schema 由 Python 的 `capability/specs.py` 持有，
 * Java 再装配一份只会造成两套事实。
 *
 * <p>`computer` 键名保持不变（32 组 golden 提示词表的键序依赖它），
 * 但语义是「该用户桌面能力在线」，即桥上有任一 `desk_*`。
 */
@Component
@RequiredArgsConstructor
public class ToolManager {

    private final AgentBridgeService agentBridgeService;

    @Value("${lianyu.tools.chat.enabled:true}")
    private boolean chatToolsEnabled;

    @Value("${lianyu.memory.agentic.enabled:true}")
    private boolean memoryAgenticEnabled;

    @Value("${lianyu.tools.recent-activity.enabled:true}")
    private boolean recentActivityEnabled;

    @Value("${lianyu.tools.write-diary.enabled:true}")
    private boolean writeDiaryEnabled;

    /**
     * 桌面能力是否在线：桥上有任一 `desk_*` 原子能力即算在线。
     *
     * <p>语义变更（能力层重构）：旧版判断「桥上有 `computer_task`」—— 那是外借引擎的
     * 粗粒度黑盒工具，已退役。现在客户端注册的是一组 `desk_*`，只要有一个在线，
     * 模型这一轮就该拿到整套桌面能力（Python 侧按 `capability_names()` 决定具体给哪些）。
     */
    boolean hasDesktopCapability(Long userId) {
        if (userId == null) {
            return false;
        }
        return agentBridgeService.availableTools(userId).stream()
                .anyMatch(t -> t.name() != null && t.name().startsWith("desk_"));
    }

    /** 工具提示的开关事实。`computer` 依赖桌面桥接的实时会话（进程内内存态），只能由 Java 提供。 */
    public Map<String, Object> promptHintFlags(Long userId) {
        return promptHintFlags(userId, null);
    }

    /**
     * 带角色上下文的开关事实。
     *
     * <p>`computer` = 全局能力在线 **且** 这个角色被允许操控电脑（`settings.desktopEnabled`）。
     * 两个闸门是「与」的关系：
     * <ul>
     *   <li>全局关 → 客户端根本没注册 desk_*，模型看不到任何桌面能力；</li>
     *   <li>角色关 → 即使能力在线，这个角色也拿不到 desk_*（只能聊天）。</li>
     * </ul>
     * 角色为 null（无角色上下文的场景）按「不允许」处理 —— 宁可不给，也不要误开。
     */
    public Map<String, Object> promptHintFlags(Long userId, Character character) {
        // 两个事实分开取，别用 && 短路：短路会让「能力是否在线」这条事实不可观测
        //（踩过：单测里 stub 因为短路而没被调用）。
        boolean desktopOnline = hasDesktopCapability(userId);
        boolean characterAllowsDesktop = CharacterPreferenceResolver.desktopEnabled(character);
        Map<String, Object> flags = new java.util.LinkedHashMap<>();
        flags.put("chat", chatToolsEnabled);
        flags.put("memory", memoryAgenticEnabled);
        flags.put("recent", recentActivityEnabled);
        flags.put("diary", writeDiaryEnabled);
        flags.put("computer", desktopOnline && characterAllowsDesktop);
        return flags;
    }
}

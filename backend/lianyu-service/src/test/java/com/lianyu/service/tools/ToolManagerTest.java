package com.lianyu.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.lianyu.dao.entity.Character;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 开关事实：只有「哪些聊天工具开着」这一件事。
 *
 * <p>2026-10-25 桌面能力层下线后，`computer` 恒为 false —— 保留键名只为不改
 * Python 侧 `build_tool_hint` 的键序契约（chat,memory,recent,diary,computer）。
 * 因此原先「桥上有 desk_* && 角色 opt-in 才为 true」的两组断言已失效，
 * 改为「无论桥/角色怎么配，`computer` 都必须是 false」。
 */
class ToolManagerTest {

    private ToolManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolManager();
        ReflectionTestUtils.setField(manager, "chatToolsEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryAgenticEnabled", true);
        ReflectionTestUtils.setField(manager, "recentActivityEnabled", true);
        ReflectionTestUtils.setField(manager, "writeDiaryEnabled", true);
    }

    /** 老角色 settings 里可能还残留 desktopEnabled=true（数据库列保留），Java 侧不该再读它。 */
    private Character legacyOptInCharacter() {
        Character character = new Character();
        Map<String, Object> settings = new HashMap<>();
        settings.put("desktopEnabled", true);
        character.setSettings(settings);
        return character;
    }

    @Test
    void chatToolFlagsFollowConfiguration() {
        Map<String, Object> flags = manager.promptHintFlags(7L, legacyOptInCharacter());
        assertThat(flags).containsEntry("chat", true)
                .containsEntry("memory", true)
                .containsEntry("recent", true)
                .containsEntry("diary", true);
    }

    @Test
    void computerFlagIsAlwaysFalseEvenWhenCharacterOptedIn() {
        // 角色 settings 里还写着 desktopEnabled=true，但能力层已删除 → 必须是 false
        assertThat(manager.promptHintFlags(7L, legacyOptInCharacter()))
                .containsEntry("computer", false);
    }

    @Test
    void computerFlagIsFalseWithoutCharacterContext() {
        // 无角色上下文：同样不给
        assertThat(manager.promptHintFlags(7L, null)).containsEntry("computer", false);
        assertThat(manager.promptHintFlags(7L)).containsEntry("computer", false);
    }

    @Test
    void keyOrderStaysStableForGoldenHints() {
        // Python 的 `tool_hints_golden.json` 依赖这五个键的顺序，删键会打乱 golden 位串
        assertThat(manager.promptHintFlags(7L, null).keySet())
                .containsExactly("chat", "memory", "recent", "diary", "computer");
    }

    @Test
    void chatToolSwitchesAreRespected() {
        ReflectionTestUtils.setField(manager, "memoryAgenticEnabled", false);
        ReflectionTestUtils.setField(manager, "writeDiaryEnabled", false);
        Map<String, Object> flags = manager.promptHintFlags(7L, null);
        assertThat(flags).containsEntry("memory", false).containsEntry("diary", false);
    }
}

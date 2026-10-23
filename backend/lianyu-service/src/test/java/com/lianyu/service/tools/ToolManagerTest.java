package com.lianyu.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.Character;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.tools.bridge.AgentBridgeService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 开关事实：只有「哪些能力开着」这一件事。
 *
 * <p>两个闸门是「与」的关系：
 * <ul>
 *   <li>`computer` = 客户端注册了 desk_*（全局能力在线）**且** 该角色 `settings.desktopEnabled`；</li>
 *   <li>角色为 null（无角色上下文）按不允许处理。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ToolManagerTest {

    @Mock
    private AgentBridgeService agentBridgeService;

    private ToolManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolManager(agentBridgeService);
        ReflectionTestUtils.setField(manager, "chatToolsEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryAgenticEnabled", true);
        ReflectionTestUtils.setField(manager, "recentActivityEnabled", true);
        ReflectionTestUtils.setField(manager, "writeDiaryEnabled", true);
    }

    private Character character(boolean desktopEnabled) {
        Character character = new Character();
        Map<String, Object> settings = new HashMap<>();
        settings.put(CharacterPreferenceResolver.KEY_DESKTOP_ENABLED, desktopEnabled);
        character.setSettings(settings);
        return character;
    }

    @Test
    void flagsWithoutDesktopBridgeMarkComputerFalse() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of());
        Map<String, Object> flags = manager.promptHintFlags(7L, character(true));
        assertThat(flags).containsEntry("chat", true)
                .containsEntry("memory", true)
                .containsEntry("recent", true)
                .containsEntry("diary", true)
                .containsEntry("computer", false);
    }

    @Test
    void computerFlagRequiresBothSwitchAndCharacterOptIn() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("desk_click", "desc", "{}", false)));

        assertThat(manager.promptHintFlags(7L, character(true)))
                .containsEntry("computer", true);
        // 角色关：能力在线也拿不到
        assertThat(manager.promptHintFlags(7L, character(false)))
                .containsEntry("computer", false);
        // 老角色（settings 里没有这个键）默认关
        Character legacy = new Character();
        legacy.setSettings(new HashMap<>());
        assertThat(manager.promptHintFlags(7L, legacy)).containsEntry("computer", false);
        // 无角色上下文：不给
        assertThat(manager.promptHintFlags(7L, null)).containsEntry("computer", false);
    }

    @Test
    void retiredComputerTaskToolNoLongerFlipsComputerFlag() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("computer_task", "desc", "{}", false)));
        assertThat(manager.promptHintFlags(7L, character(true))).containsEntry("computer", false);
    }

    @Test
    void singleArgOverloadMeansNoCharacterContext() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("desk_click", "desc", "{}", false)));
        assertThat(manager.promptHintFlags(7L)).containsEntry("computer", false);
    }
}

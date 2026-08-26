package com.lianyu.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lianyu.service.tools.bridge.AgentBridgeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ToolManagerTest {

    @Mock
    private TimeTool timeTool;
    @Mock
    private WeatherTool weatherTool;
    @Mock
    private MemorySearchTool memorySearchTool;
    @Mock
    private RecentActivityTool recentActivityTool;
    @Mock
    private AgentBridgeService agentBridgeService;

    private ToolManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolManager(
                timeTool, weatherTool, memorySearchTool, recentActivityTool, agentBridgeService);
        ReflectionTestUtils.setField(manager, "chatToolsEnabled", true);
        ReflectionTestUtils.setField(manager, "weatherEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryAgenticEnabled", true);
        ReflectionTestUtils.setField(manager, "recentActivityEnabled", true);
    }

    @Test
    void hintWithoutDesktopBridgeDoesNotMentionComputerTask() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of());
        String hint = manager.buildToolsPromptHint(7L);
        assertThat(hint).contains("get_current_time");
        assertThat(hint).doesNotContain("computer_task");
        assertThat(hint).contains("避免每条寒暄都调");
        assertThat(hint).doesNotContain("避免每条消息都调用");
    }

    @Test
    void hintWithComputerTaskRequiresSameTurnCallOnFollowUp() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("computer_task", "desc", "{}", false)));
        String hint = manager.buildToolsPromptHint(7L);
        assertThat(hint).contains("computer_task");
        assertThat(hint).contains("本轮必须调用");
        assertThat(hint).contains("禁止只回复");
        assertThat(hint).contains("改口换任务");
        assertThat(hint).doesNotContain("避免每条消息都调用");
    }
}

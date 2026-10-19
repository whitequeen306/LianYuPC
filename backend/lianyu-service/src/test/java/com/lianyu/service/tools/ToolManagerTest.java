package com.lianyu.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lianyu.service.tools.bridge.AgentBridgeService;
import java.util.List;
import java.util.Map;
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
    private RecentActivityTool recentActivityTool;
    @Mock
    private WriteDiaryTool writeDiaryTool;
    @Mock
    private AgentBridgeService agentBridgeService;

    private ToolManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolManager(
                timeTool, weatherTool, recentActivityTool, writeDiaryTool,
                agentBridgeService);
        ReflectionTestUtils.setField(manager, "chatToolsEnabled", true);
        ReflectionTestUtils.setField(manager, "memoryAgenticEnabled", true);
        ReflectionTestUtils.setField(manager, "recentActivityEnabled", true);
        ReflectionTestUtils.setField(manager, "writeDiaryEnabled", true);
    }

    @Test
    void flagsWithoutDesktopBridgeMarkComputerFalse() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of());
        Map<String, Object> flags = manager.promptHintFlags(7L);
        assertThat(flags).containsEntry("chat", true)
                .containsEntry("memory", true)
                .containsEntry("recent", true)
                .containsEntry("diary", true)
                .containsEntry("computer", false);
    }

    @Test
    void flagsWithComputerTaskMarkComputerTrue() {
        when(agentBridgeService.availableTools(7L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("computer_task", "desc", "{}", false)));
        assertThat(manager.promptHintFlags(7L)).containsEntry("computer", true);
    }
}

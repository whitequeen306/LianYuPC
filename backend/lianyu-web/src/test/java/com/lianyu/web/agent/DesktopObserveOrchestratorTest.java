package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.VisionChatService;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Strangler 两模式行为测试（切流安全性依据）。
 *
 * <p>为什么必须有：`AGENT` 模式一旦把「Python 结果为空」当成正常返回，
 * 用户就会看到空回复。本测试固化「AGENT 空结果必须回退 Java」这条安全网。
 */
class DesktopObserveOrchestratorTest {

    private VisionChatService visionChatService;
    private AgentRuntimeClient agentClient;
    private ApiKeyVaultService vaultService;
    private DesktopObserveOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        visionChatService = Mockito.mock(VisionChatService.class);
        agentClient = Mockito.mock(AgentRuntimeClient.class);
        vaultService = Mockito.mock(ApiKeyVaultService.class);
        orchestrator = new DesktopObserveOrchestrator(visionChatService, agentClient, vaultService);
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.OFF));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder()
                        .provider("dashscope")
                        .baseUrl("https://fake/v1")
                        .modelDefault("m")
                        .apiKey("k")
                        .build());
    }

    private static AgentRuntimeProperties props(AgentRuntimeProperties.Mode mode) {
        return new AgentRuntimeProperties(mode, "http://agent:8001");
    }

    private List<MessageDto> history() {
        MessageDto dto = new MessageDto();
        dto.setRole("user");
        dto.setContent("在忙吗");
        return List.of(dto);
    }

    // ── 端口语义（service 层按此决定是否回退 Java）──

    @Test
    void portReturnsNullInOffMode() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.OFF));
        com.lianyu.service.agent.AgentTurnResult result = orchestrator.proactiveTurn(1L, "PROACTIVE", "persona", "task",
                List.of(Map.of("role", "user", "content", "hi")), "dashscope", "m", 7L);
        assertNull(result);
        assertFalse(orchestrator.agentActive());
        verify(agentClient, never()).proactiveTurn(
                anyLong(), anyString(), anyString(), anyString(), anyList(),
                anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class), any(), anyList());
    }

    @Test
    void portReturnsAgentContentInAgentMode() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(agentClient.proactiveTurnResult(anyLong(), anyString(), anyString(), anyString(), anyList(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class), any()))
                .thenReturn(new com.lianyu.service.agent.AgentTurnResult("今晚早点睡呀", 42));
        com.lianyu.service.agent.AgentTurnResult result = orchestrator.proactiveTurn(1L, "PROACTIVE", "persona", "task",
                List.of(Map.of("role", "user", "content", "hi")), "dashscope", "m", 7L);
        assertEquals("今晚早点睡呀", result.content());
        assertEquals(42, result.totalTokens());
        assertTrue(orchestrator.agentActive());
    }

    @Test
    void portReturnsNullWhenAgentEmptySoCallerFallsBack() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(agentClient.proactiveTurnResult(anyLong(), anyString(), anyString(), anyString(), anyList(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class), any()))
                .thenReturn(null);
        // 关键安全网：返回 null → service 侧继续走 Java 原链路
        assertNull(orchestrator.proactiveTurn(1L, "PROACTIVE", "persona", "task",
                List.of(Map.of("role", "user", "content", "hi")), "dashscope", "m", 7L));
    }

    @Test
    void portReturnsNullWhenAgentBlank() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(agentClient.proactiveTurnResult(anyLong(), anyString(), anyString(), anyString(), anyList(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class), any()))
                .thenReturn(new com.lianyu.service.agent.AgentTurnResult("   ", null));
        assertNull(orchestrator.proactiveTurn(1L, "PROACTIVE", "persona", "task",
                List.of(Map.of("role", "user", "content", "hi")), "dashscope", "m", 7L));
    }

    @Test
    void modeReportedForLogging() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.OFF));
        assertEquals("OFF", orchestrator.mode());
    }

    // ── 桌面感知：两模式分派 ──

    @Test
    void observe_offMode_usesJavaOnly() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.OFF));
        when(visionChatService.observeDesktop(1L, "img", "t", "p", "dashscope", "m"))
                .thenReturn("java 问候");
        String result = orchestrator.observe(1L, "img", "t", "p", "dashscope", "m");
        assertEquals("java 问候", result);
        verify(agentClient, never()).desktopObserve(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void observe_agentMode_returnsAgentResultWithoutCallingJava() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(agentClient.desktopObserve(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("agent 问候");
        String result = orchestrator.observe(1L, "img", "t", "p", "dashscope", "m");
        assertEquals("agent 问候", result);
        verify(visionChatService, never()).observeDesktop(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void observe_agentModeEmpty_fallsBackToJava() {
        when(agentClient.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(agentClient.desktopObserve(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(null);
        when(visionChatService.observeDesktop(1L, "img", "t", "p", "dashscope", "m"))
                .thenReturn("java 兜底");
        assertEquals("java 兜底", orchestrator.observe(1L, "img", "t", "p", "dashscope", "m"));
    }

}

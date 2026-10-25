package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * agent 编排行为测试（OFF 退役后只有一条路径）。
 */
class DesktopObserveOrchestratorTest {
    /** 工具清单来源的桩：测试里不需要真去打 /agent/capabilities。 */
    private static AgentToolCatalog stubCatalog() {
        AgentToolCatalog catalog = org.mockito.Mockito.mock(AgentToolCatalog.class);
        org.mockito.Mockito.when(catalog.chatTools()).thenReturn(java.util.List.of(
                "get_current_time", "get_weather", "memory_search", "get_my_recent_life"));
        return catalog;
    }

    private AgentRuntimeClient agentClient;
    private ApiKeyVaultService vaultService;
    private DesktopObserveOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        agentClient = Mockito.mock(AgentRuntimeClient.class);
        vaultService = Mockito.mock(ApiKeyVaultService.class);
        orchestrator = new DesktopObserveOrchestrator(agentClient, stubCatalog(), vaultService);
        when(agentClient.properties()).thenReturn(new AgentRuntimeProperties("http://agent:8001"));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder()
                        .provider("dashscope")
                        .baseUrl("https://fake/v1")
                        .modelDefault("m")
                        .apiKey("k")
                        .build());
    }

    @Test
    void portReturnsAgentContent() {
        when(agentClient.proactiveTurnResult(anyLong(), anyString(), anyString(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class), any()))
                .thenReturn(new com.lianyu.service.agent.AgentTurnResult("今晚早点睡呀", 42));
        com.lianyu.service.agent.AgentTurnResult result = orchestrator.proactiveTurn(1L, "PROACTIVE", "persona", "task",
                List.of(Map.of("role", "user", "content", "hi")), "dashscope", "m", 7L);
        assertEquals("今晚早点睡呀", result.content());
        assertEquals(42, result.totalTokens());
        assertTrue(orchestrator.agentActive());
    }

    @Test
    void portReturnsNullWhenAgentEmpty() {
        when(agentClient.proactiveTurnResult(anyLong(), anyString(), anyString(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class), any()))
                .thenReturn(null);
        assertNull(orchestrator.proactiveTurn(1L, "PROACTIVE", "persona", "task",
                List.of(Map.of("role", "user", "content", "hi")), "dashscope", "m", 7L));
    }

    @Test
    void portReturnsNullWhenAgentBlank() {
        when(agentClient.proactiveTurnResult(anyLong(), anyString(), anyString(), anyString(), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class), any()))
                .thenReturn(new com.lianyu.service.agent.AgentTurnResult("   ", null));
        assertNull(orchestrator.proactiveTurn(1L, "PROACTIVE", "persona", "task",
                List.of(Map.of("role", "user", "content", "hi")), "dashscope", "m", 7L));
    }

    @Test
    void modeReportedForLogging() {
        assertEquals("AGENT", orchestrator.mode());
    }

    @Test
    void observe_returnsAgentResult() {
        when(agentClient.desktopObserve(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("agent 问候");
        assertEquals("agent 问候", orchestrator.observe(1L, "img", "t", "p", "dashscope", "m"));
    }

    @Test
    void observe_agentEmpty_returnsNull() {
        when(agentClient.desktopObserve(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(null);
        assertNull(orchestrator.observe(1L, "img", "t", "p", "dashscope", "m"));
    }
}

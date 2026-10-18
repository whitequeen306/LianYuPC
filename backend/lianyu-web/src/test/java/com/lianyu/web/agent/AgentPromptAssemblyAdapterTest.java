package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.agent.PromptAssemblyRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AGENT 模式下的 prompt 接管：成功时返回 Python 结果，任何异常都必须**安静回退**。
 */
class AgentPromptAssemblyAdapterTest {

    private final ObjectMapper om = new ObjectMapper();

    private PromptAssemblyRequest request() {
        return new PromptAssemblyRequest(7L, 51L, 53L, "甘雨", Map.of("city", "上海"), null,
                "SINGLE", "zh", "full", true, null, true, true, 1, "工具提示");
    }

    private AgentRuntimeClient client() {
        AgentRuntimeClient client = mock(AgentRuntimeClient.class);
        when(client.readTree(anyString())).thenAnswer(inv -> om.readTree((String) inv.getArgument(0)));
        return client;
    }

    @Test
    void returnsPromptInAgentMode() {
        AgentRuntimeClient client = client();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"systemPrompt\":\"来自 Python 的 prompt\"}");

        var adapter = new AgentPromptAssemblyAdapter(client, "AGENT");

        assertEquals("来自 Python 的 prompt", adapter.assembleIfEnabled(request()));
    }

    @Test
    void offModeNeverCallsAgent() {
        AgentRuntimeClient client = client();
        var adapter = new AgentPromptAssemblyAdapter(client, "OFF");

        assertNull(adapter.assembleIfEnabled(request()));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void errorResponseFallsBack() {
        AgentRuntimeClient client = client();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"error\":\"assemble_failed\"}");

        assertNull(new AgentPromptAssemblyAdapter(client, "AGENT").assembleIfEnabled(request()));
    }

    @Test
    void blankPromptFallsBack() {
        AgentRuntimeClient client = client();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"systemPrompt\":\"  \"}");

        assertNull(new AgentPromptAssemblyAdapter(client, "AGENT").assembleIfEnabled(request()));
    }

    @Test
    void nullResponseFallsBack() {
        AgentRuntimeClient client = client();
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn(null);

        assertNull(new AgentPromptAssemblyAdapter(client, "AGENT").assembleIfEnabled(request()));
    }

    @Test
    void exceptionFallsBackInsteadOfThrowing() {
        AgentRuntimeClient client = client();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenThrow(new IllegalStateException("boom"));

        assertNull(new AgentPromptAssemblyAdapter(client, "AGENT").assembleIfEnabled(request()));
    }

    @Test
    void bodyUsesInternalDataAndScene() {
        Map<String, Object> body = AgentPromptAssemblyAdapter.bodyOf(request());
        assertEquals(true, body.get("useInternalData"));
        assertEquals("SINGLE", body.get("scene"));
        assertEquals(51L, body.get("characterId"));
        assertEquals(53L, body.get("conversationId"));
        assertEquals(7L, body.get("userId"));
        assertEquals(1, body.get("maxPieces"));
        assertTrue(body.containsKey("toolHint"));
    }

    @Test
    void nullRequestIsIgnored() {
        AgentRuntimeClient client = client();
        assertNull(new AgentPromptAssemblyAdapter(client, "AGENT").assembleIfEnabled(null));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }
}

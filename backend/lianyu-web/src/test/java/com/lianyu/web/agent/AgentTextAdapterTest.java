package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 通用文本端口适配器测试。
 *
 * <p>核心断言：**任何不该接管的情形都必须返回 null** —— 调用方据此回退 Java，
 * 少接管一次只是慢一点，多接管一次会直接让日记生成失败。
 */
class AgentTextAdapterTest {

    private AgentRuntimeClient client;
    private ApiKeyVaultService vaultService;
    private AgentTextAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        vaultService = mock(ApiKeyVaultService.class);
        adapter = new AgentTextAdapter(client, vaultService);
    }

    /** 用真实 ObjectMapper 解析，避免 mock 的 readTree 内部 objectMapper 为 null。 */
    private void stubReadTree() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        when(client.readTree(anyString())).thenAnswer(inv -> {
            String raw = inv.getArgument(0);
            return om.readTree(raw == null ? "{}" : raw);
        });
    }

    private static AgentRuntimeProperties props(AgentRuntimeProperties.Mode mode) {
        return new AgentRuntimeProperties(mode, "http://agent:8001", true);
    }

    @Test
    void offModeDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.OFF));
        assertNull(adapter.complete(1L, "sys", "user", "dashscope", "m", 0.85));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void shadowModeDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.SHADOW));
        assertNull(adapter.complete(1L, "sys", "user", "dashscope", "m", 0.85));
    }

    @Test
    void missingVaultDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(null);
        assertNull(adapter.complete(1L, "sys", "user", "dashscope", "m", 0.85));
    }

    @Test
    void vaultExceptionDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenThrow(new IllegalStateException("decrypt"));
        assertNull(adapter.complete(1L, "sys", "user", "dashscope", "m", 0.85));
    }

    @Test
    void agentModeReturnsContent() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("https://fake/v1")
                        .apiKey("k").modelDefault("m").build());
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"content\":\"{\\\"title\\\":\\\"雨天\\\"}\"}");
        stubReadTree();

        String result = adapter.complete(1L, "sys", "user", "dashscope", "m", 0.85);
        assertEquals("{\"title\":\"雨天\"}", result);
    }

    @Test
    void agentErrorFallsBackToJava() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("https://fake/v1")
                        .apiKey("k").modelDefault("m").build());
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn("{\"error\":\"empty_content\"}");
        stubReadTree();

        assertNull(adapter.complete(1L, "sys", "user", "dashscope", "m", 0.85));
    }

    @Test
    void nullResponseFallsBackToJava() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("https://fake/v1")
                        .apiKey("k").modelDefault("m").build());
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn(null);

        assertNull(adapter.complete(1L, "sys", "user", "dashscope", "m", 0.85));
    }
}

package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.agent.AgentMemoryPort.AgentMemoryMessage;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 记忆端口适配器的接管判据测试。
 *
 * <p>返回 false 时 Java 会走原链路，所以**误判 true 的代价是记忆丢失**，
 * 每一次「不确定」都必须落到 false。
 */
class AgentMemoryAdapterTest {

    private AgentRuntimeClient client;
    private ApiKeyVaultService vaultService;
    private AgentMemoryAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        vaultService = mock(ApiKeyVaultService.class);
        adapter = new AgentMemoryAdapter(client, vaultService);
    }

    private static AgentRuntimeProperties props(AgentRuntimeProperties.Mode mode) {
        return new AgentRuntimeProperties(mode, "http://agent:8001");
    }

    private static List<AgentMemoryMessage> messages() {
        return List.of(new AgentMemoryMessage(1L, "USER", "我叫小林"));
    }

    private void stubVault() {
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("https://fake/v1")
                        .apiKey("k").modelDefault("m").build());
    }

    private void stubReadTree() {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        when(client.readTree(anyString())).thenAnswer(inv -> {
            String raw = inv.getArgument(0);
            return om.readTree(raw == null ? "{}" : raw);
        });
    }

    @Test
    void offModeDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.OFF));
        assertFalse(adapter.extractAndWriteIfActive(1L, 3L, messages(), "dashscope", "m"));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void emptyMessagesDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        assertFalse(adapter.extractAndWriteIfActive(1L, 3L, List.of(), "dashscope", "m"));
    }

    @Test
    void nullCharacterDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        assertFalse(adapter.extractAndWriteIfActive(1L, null, messages(), "dashscope", "m"));
    }

    @Test
    void missingVaultDoesNotTakeOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(null);
        when(vaultService.resolvePreferredUserVault(anyLong())).thenReturn(null);
        assertFalse(adapter.extractAndWriteIfActive(1L, 3L, messages(), "dashscope", "m"));
    }

    @Test
    void networkFailureFallsBack() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        stubVault();
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn(null);
        assertFalse(adapter.extractAndWriteIfActive(1L, 3L, messages(), "dashscope", "m"));
    }

    @Test
    void agentErrorFallsBack() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        stubVault();
        stubReadTree();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"error\":\"extract_failed\"}");
        assertFalse(adapter.extractAndWriteIfActive(1L, 3L, messages(), "dashscope", "m"));
    }

    @Test
    void agentSuccessTakesOver() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        stubVault();
        stubReadTree();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"created\":1,\"updated\":0,\"extracted\":1}");
        assertTrue(adapter.extractAndWriteIfActive(1L, 3L, messages(), "dashscope", "m"));
    }
}

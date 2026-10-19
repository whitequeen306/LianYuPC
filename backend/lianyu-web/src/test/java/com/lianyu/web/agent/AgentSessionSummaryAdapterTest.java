package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 会话摘要接管：成功返回正文，任何异常安静回退。 */
class AgentSessionSummaryAdapterTest {

    private final ObjectMapper om = new ObjectMapper();
    private AgentRuntimeClient client;
    private ApiKeyVaultService vaultService;
    private AgentSessionSummaryAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        when(client.properties()).thenReturn(new AgentRuntimeProperties("http://agent:8001"));
        when(client.isHealthy()).thenReturn(true);
        when(client.readTree(anyString())).thenAnswer(inv -> om.readTree((String) inv.getArgument(0)));
        vaultService = mock(ApiKeyVaultService.class);
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().id(9L).baseUrl("https://fake/v1")
                        .apiKey("enc").modelDefault("m-default").build());
        when(vaultService.decryptKeyForChat(9L)).thenReturn("sk-real");
        adapter = new AgentSessionSummaryAdapter(client, vaultService);
    }

    private List<com.lianyu.service.agent.SessionSummaryPort.SummaryMessage> messages() {
        return List.of(new com.lianyu.service.agent.SessionSummaryPort.SummaryMessage("USER", "明天去外滩"));
    }

    @Test
    void mergeReturnsText() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"text\":\"约好明天去外滩\"}");

        assertEquals("约好明天去外滩", adapter.generate(
                7L, "p", null, "merge", "", messages(), 0));
    }

    @Test
    void bodyCarriesDecryptedKeyAndMode() {
        when(client.postJson(anyString(), anyLong(), anyMap())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = inv.getArgument(2);
            assertEquals("sk-real", body.get("apiKey"));
            assertEquals("m-default", body.get("model"));
            assertEquals("compress", body.get("mode"));
            assertEquals(600, body.get("targetChars"));
            assertEquals(0, ((List<?>) body.get("messages")).size(), "压缩模式没有新增消息");
            return "{\"text\":\"压缩结果\"}";
        });

        assertEquals("压缩结果", adapter.generate(7L, "p", null, "compress", "旧摘要",
                List.of(), 600));
    }

    @Test
    void errorFallsBack() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"error\":\"summary_failed\"}");

        assertNull(adapter.generate(7L, "p", "m", "merge", "", messages(), 0));
    }

    @Test
    void blankTextFallsBack() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"text\":\"   \"}");

        assertNull(adapter.generate(7L, "p", "m", "merge", "", messages(), 0));
    }

    @Test
    void unhealthySkipsRequest() {
        when(client.isHealthy()).thenReturn(false);

        assertNull(adapter.generate(7L, "p", "m", "merge", "", messages(), 0));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void vaultFailureFallsBack() {
        when(vaultService.resolveForChat(anyLong(), any()))
                .thenThrow(new IllegalStateException("no vault"));

        assertNull(adapter.generate(7L, "p", "m", "merge", "", messages(), 0));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void exceptionFallsBack() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenThrow(new IllegalStateException("boom"));

        assertNull(adapter.generate(7L, "p", "m", "merge", "", messages(), 0));
    }
}

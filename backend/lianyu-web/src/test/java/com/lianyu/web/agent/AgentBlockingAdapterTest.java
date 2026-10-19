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
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 阻塞调用接管：成功时返回 Python 结果，任何异常都必须**安静回退** Java。
 *
 * <p>这条路径覆盖群聊 judge / 语音 / 日记 / 摘要合并 / 动态评论等 13 个调用点，
 * 所以边界行为比性能更重要。
 */
class AgentBlockingAdapterTest {

    private final ObjectMapper om = new ObjectMapper();
    private AgentRuntimeClient client;
    private ApiKeyVaultService vaultService;
    private AgentBlockingAdapter adapter;

    private List<MessageDto> messages() {
        MessageDto system = new MessageDto();
        system.setRole("system");
        system.setContent("你是测试角色");
        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent("你好");
        return List.of(system, user);
    }

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        when(client.properties()).thenReturn(new AgentRuntimeProperties(
                AgentRuntimeProperties.Mode.AGENT, "http://agent:8001"));
        when(client.isHealthy()).thenReturn(true);
        when(client.readTree(anyString())).thenAnswer(inv -> om.readTree((String) inv.getArgument(0)));
        vaultService = mock(ApiKeyVaultService.class);
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().id(9L).baseUrl("https://fake/v1")
                        .apiKey("enc").modelDefault("m-default").build());
        when(vaultService.decryptKeyForChat(9L)).thenReturn("sk-real");
        adapter = new AgentBlockingAdapter(client, vaultService);
    }

    @Test
    void takesOverAndReturnsContent() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"content\":\"你好呀\"}");

        assertEquals("你好呀", adapter.complete(7L, messages(), "p", null, 0.5, 512));
    }

    @Test
    void bodyCarriesDecryptedKeyAndResolvedModel() {
        when(client.postJson(anyString(), anyLong(), anyMap())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = inv.getArgument(2);
            assertEquals("sk-real", body.get("apiKey"), "凭据由 Java 解密后传入");
            assertEquals("m-default", body.get("model"), "未指定 model 时用 vault 默认");
            assertEquals(0.5, body.get("temperature"));
            assertEquals(512, body.get("maxTokens"));
            assertEquals(2, ((List<?>) body.get("messages")).size());
            return "{\"content\":\"ok\"}";
        });

        assertEquals("ok", adapter.complete(7L, messages(), "p", null, 0.5, 512));
    }

    @Test
    void errorResponseFallsBack() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"error\":\"chat_failed\"}");

        assertNull(adapter.complete(7L, messages(), "p", "m", 0.7, 1024));
    }

    @Test
    void blankContentFallsBack() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"content\":\"  \"}");

        assertNull(adapter.complete(7L, messages(), "p", "m", 0.7, 1024));
    }

    @Test
    void exceptionFallsBackInsteadOfThrowing() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenThrow(new IllegalStateException("boom"));

        assertNull(adapter.complete(7L, messages(), "p", "m", 0.7, 1024));
    }

    @Test
    void unhealthySkipsRequest() {
        when(client.isHealthy()).thenReturn(false);

        assertNull(adapter.complete(7L, messages(), "p", "m", 0.7, 1024));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void vaultFailureFallsBack() {
        when(vaultService.resolveForChat(anyLong(), any()))
                .thenThrow(new IllegalStateException("no vault"));

        assertNull(adapter.complete(7L, messages(), "p", "m", 0.7, 1024));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void emptyMessagesSkipsRequest() {
        assertNull(adapter.complete(7L, List.of(), "p", "m", 0.7, 1024));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void payloadDropsMessagesWithoutRole() {
        MessageDto broken = new MessageDto();
        broken.setContent("无角色");
        var payload = AgentBlockingAdapter.toPayload(List.of(broken));
        assertTrue(payload.isEmpty());
    }
}

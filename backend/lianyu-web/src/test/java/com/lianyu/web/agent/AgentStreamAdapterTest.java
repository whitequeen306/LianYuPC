package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * 流式适配器的「接管 / 不接管」判据测试。
 *
 * <p>这是灰度安全网：只要返回 null，Facade 就会走 Java 原链路。
 * 所以「什么情况下返回 null」必须精确——多接管一次就可能让用户看到空回复。
 */
class AgentStreamAdapterTest {
    /** 工具清单来源的桩：测试里不需要真去打 /agent/capabilities。 */
    private static AgentToolCatalog stubCatalog() {
        AgentToolCatalog catalog = org.mockito.Mockito.mock(AgentToolCatalog.class);
        org.mockito.Mockito.when(catalog.chatTools()).thenReturn(java.util.List.of(
                "get_current_time", "get_weather", "memory_search", "get_my_recent_life"));
        return catalog;
    }

    private AgentRuntimeClient client;
    private AgentStreamBridge bridge;
    private ApiKeyVaultService vaultService;
    private AgentStreamAdapter adapter;
    private com.lianyu.service.agent.AgentStreamCallback callback;

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        when(client.isHealthy()).thenReturn(true);
        bridge = mock(AgentStreamBridge.class);
        vaultService = mock(ApiKeyVaultService.class);
        adapter = new AgentStreamAdapter(client, stubCatalog(), bridge, vaultService);
        callback = mock(com.lianyu.service.agent.AgentStreamCallback.class);
        when(bridge.newRelay(any(), any())).thenReturn(mock(AgentStreamBridge.Relay.class));
    }

    private static List<MessageDto> messages() {
        MessageDto system = new MessageDto();
        system.setRole("system");
        system.setContent("人设");
        MessageDto user = new MessageDto();
        user.setRole("USER");
        user.setContent("在吗");
        return List.of(system, user);
    }

    private static AgentRuntimeProperties props() {
        return new AgentRuntimeProperties("http://agent:8001");
    }

    private SseEmitter call() {
        return adapter.streamIfActive(1L, "人设", messages(), "dashscope", "m", null, 7L, 1024, callback);
    }

    @Test
    void doesNotTakeOverWhenVaultMissing() {
        when(client.properties()).thenReturn(props());
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(null);
        // 无用户文本模型 → 交给 Java（那边会给出准确错误文案）
        assertNull(call());
    }

    @Test
    void doesNotTakeOverWhenVaultResolveThrows() {
        when(client.properties()).thenReturn(props());
        when(vaultService.resolveForChat(anyLong(), any())).thenThrow(new IllegalStateException("decrypt failed"));
        assertNull(call());
    }

    @Test
    void doesNotTakeOverWhenCredentialsBlank() {
        when(client.properties()).thenReturn(props());
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("  ").apiKey("k").build());
        assertNull(call());
    }

    @Test
    void takesOverInAgentModeAndDropsSystemFromPayload() {
        when(client.properties()).thenReturn(props());
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("https://fake/v1")
                        .apiKey("k").modelDefault("m").build());
        when(client.streamChat(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any(Double.class), any(Integer.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<java.util.Map<String, String>> payload = inv.getArgument(2);
                    // system 由 systemPrompt 单独传，不得混进 messages
                    assertNotNull(payload);
                    org.junit.jupiter.api.Assertions.assertEquals(1, payload.size());
                    org.junit.jupiter.api.Assertions.assertEquals("user", payload.get(0).get("role"));
                    return Flux.empty();
                });

        SseEmitter emitter = call();
        assertNotNull(emitter, "AGENT 模式必须接管");
    }
}

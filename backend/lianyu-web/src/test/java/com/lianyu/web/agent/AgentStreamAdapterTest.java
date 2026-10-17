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

    private AgentRuntimeClient client;
    private AgentStreamBridge bridge;
    private ApiKeyVaultService vaultService;
    private AgentStreamAdapter adapter;
    private AiChatService.StreamCallback callback;

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        bridge = mock(AgentStreamBridge.class);
        vaultService = mock(ApiKeyVaultService.class);
        adapter = new AgentStreamAdapter(client, bridge, vaultService);
        callback = mock(AiChatService.StreamCallback.class);
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

    private static AgentRuntimeProperties props(AgentRuntimeProperties.Mode mode) {
        return new AgentRuntimeProperties(mode, "http://agent:8001", true);
    }

    private SseEmitter call() {
        return adapter.streamIfActive(1L, "人设", messages(), "dashscope", "m", null, 7L, 1024, callback);
    }

    @Test
    void doesNotTakeOverInOffMode() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.OFF));
        assertNull(call());
        verify(client, never()).streamChat(anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(Double.class), any(Integer.class));
    }

    @Test
    void doesNotTakeOverInShadowMode() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.SHADOW));
        assertNull(call());
    }

    @Test
    void doesNotTakeOverWhenVaultMissing() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(null);
        // 无用户文本模型 → 交给 Java（那边会给出准确错误文案）
        assertNull(call());
    }

    @Test
    void doesNotTakeOverWhenVaultResolveThrows() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenThrow(new IllegalStateException("decrypt failed"));
        assertNull(call());
    }

    @Test
    void doesNotTakeOverWhenCredentialsBlank() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("  ").apiKey("k").build());
        assertNull(call());
    }

    @Test
    void takesOverInAgentModeAndDropsSystemFromPayload() {
        when(client.properties()).thenReturn(props(AgentRuntimeProperties.Mode.AGENT));
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

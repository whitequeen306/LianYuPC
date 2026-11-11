package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

    private static AgentRuntimeProperties props() {
        return new AgentRuntimeProperties("http://agent:8001");
    }

    private static List<AgentMemoryMessage> messages() {
        return List.of(new AgentMemoryMessage(1L, "USER", "我叫小林"));
    }

    private void stubVault() {
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(
                VaultEntryResponse.builder().provider("dashscope").baseUrl("https://fake/v1")
                        .apiKey("k").modelDefault("m").build());
    }

    /** 通话记忆没有 provider 上下文，走的是用户默认 vault。 */
    private void stubPreferredVault() {
        when(vaultService.resolvePreferredUserVault(anyLong())).thenReturn(
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
    void emptyMessagesDoesNotTakeOver() {
        when(client.properties()).thenReturn(props());
        assertFalse(adapter.extractAndWriteIfActive(1L, 9L, 3L, List.of(), "dashscope", "m"));
    }

    @Test
    void nullCharacterDoesNotTakeOver() {
        when(client.properties()).thenReturn(props());
        assertFalse(adapter.extractAndWriteIfActive(1L, 9L, null, messages(), "dashscope", "m"));
    }

    @Test
    void missingVaultDoesNotTakeOver() {
        when(client.properties()).thenReturn(props());
        when(vaultService.resolveForChat(anyLong(), any())).thenReturn(null);
        when(vaultService.resolvePreferredUserVault(anyLong())).thenReturn(null);
        assertFalse(adapter.extractAndWriteIfActive(1L, 9L, 3L, messages(), "dashscope", "m"));
    }

    @Test
    void networkFailureFallsBack() {
        when(client.properties()).thenReturn(props());
        stubVault();
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn(null);
        assertFalse(adapter.extractAndWriteIfActive(1L, 9L, 3L, messages(), "dashscope", "m"));
    }

    @Test
    void agentErrorFallsBack() {
        when(client.properties()).thenReturn(props());
        stubVault();
        stubReadTree();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"error\":\"extract_failed\"}");
        assertFalse(adapter.extractAndWriteIfActive(1L, 9L, 3L, messages(), "dashscope", "m"));
    }

    @Test
    void agentSuccessTakesOver() {
        when(client.properties()).thenReturn(props());
        stubVault();
        stubReadTree();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"created\":1,\"updated\":0,\"extracted\":1}");
        assertTrue(adapter.extractAndWriteIfActive(1L, 9L, 3L, messages(), "dashscope", "m"));
    }

    @Test
    void passesConversationIdSoSceneCanBeClosed() {
        when(client.properties()).thenReturn(props());
        stubVault();
        stubReadTree();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"created\":0,\"updated\":0,\"extracted\":0}");
        assertTrue(adapter.extractAndWriteIfActive(1L, 9L, 3L, messages(), "dashscope", "m"));
        // 没有 conversationId，Python 就找不到「上一幕」，episode 永远不会生成。
        verify(client).postJson(
                eq("/agent/memory/extract-write"),
                eq(1L),
                argThat(body -> Long.valueOf(9L).equals(body.get("conversationId"))));
    }

    @Test
    void voiceMomentPostsTheTurnAndReportsWritten() {
        when(client.properties()).thenReturn(props());
        stubPreferredVault();
        stubReadTree();
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"written\":1,\"episodeId\":31}");

        assertTrue(adapter.writeVoiceMomentIfActive(
                1L, 9L, 3L, "今天加班到很晚，好累，你在吗？", "我在，先坐下歇一会儿。"));

        verify(client).postJson(
                eq("/agent/memory/moment"),
                eq(1L),
                argThat(body -> "今天加班到很晚，好累，你在吗？".equals(body.get("userText"))
                        && "我在，先坐下歇一会儿。".equals(body.get("replyText"))
                        && Long.valueOf(9L).equals(body.get("conversationId"))
                        && Long.valueOf(3L).equals(body.get("characterId"))));
    }

    @Test
    void voiceMomentNotWrittenIsFalse() {
        when(client.properties()).thenReturn(props());
        stubPreferredVault();
        stubReadTree();
        // 模型判定「不值得记」或 7 天内同句去重都会落到 written=0
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"written\":0,\"episodeId\":0}");
        assertFalse(adapter.writeVoiceMomentIfActive(1L, 9L, 3L, "今天加班到很晚，好累", "我在"));
    }

    @Test
    void voiceMomentNetworkFailureIsSwallowed() {
        when(client.properties()).thenReturn(props());
        stubPreferredVault();
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn(null);
        assertFalse(adapter.writeVoiceMomentIfActive(1L, 9L, 3L, "今天加班到很晚，好累", "我在"));
    }

    @Test
    void blankVoiceMomentTextDoesNotCallAgent() {
        assertFalse(adapter.writeVoiceMomentIfActive(1L, 9L, 3L, "   ", "我在"));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }
}

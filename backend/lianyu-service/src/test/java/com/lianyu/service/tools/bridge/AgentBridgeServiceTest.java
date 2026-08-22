package com.lianyu.service.tools.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.dto.RegisterAgentToolsRequest;
import com.lianyu.service.tools.ChatToolContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AgentBridgeServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private AgentBridgeService service;

    @BeforeEach
    void setUp() {
        service = new AgentBridgeService(messagingTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "callTimeoutMs", 200L);
    }

    @AfterEach
    void tearDown() {
        ChatToolContext.clear();
    }

    private RegisterAgentToolsRequest requestWith(String... names) {
        RegisterAgentToolsRequest request = new RegisterAgentToolsRequest();
        request.setTools(java.util.Arrays.stream(names).map(name -> {
            RegisterAgentToolsRequest.AgentToolSpec spec = new RegisterAgentToolsRequest.AgentToolSpec();
            spec.setName(name);
            spec.setDescription("desc of " + name);
            return spec;
        }).toList());
        return request;
    }

    @Test
    void registerThenToolsVisible() {
        service.register(7L, requestWith("computer_task", "local_echo"));

        List<AgentBridgeService.ClientTool> tools = service.availableTools(7L);
        assertThat(tools).extracting(AgentBridgeService.ClientTool::name)
                .containsExactly("computer_task", "local_echo");
        assertThat(tools.get(0).inputSchema()).contains("\"type\":\"object\"");
        assertThat(service.isOnline(7L)).isTrue();
        assertThat(service.availableTools(8L)).isEmpty();
    }

    @Test
    void rejectsIllegalToolNames() {
        assertThatThrownBy(() -> service.register(7L, requestWith("bad name!")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.register(7L, requestWith("dup", "dup")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dispatchWithoutBridgeReturnsOfflineText() {
        String result = service.dispatch(99L, "computer_task", "{}");
        assertThat(result).contains("不在线");
    }

    @Test
    void dispatchTimesOutWithFriendlyText() {
        service.register(7L, requestWith("computer_task"));
        String result = service.dispatch(7L, "computer_task", "{\"instruction\":\"打开网易云\"}");
        assertThat(result).contains("超时");
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq(AgentBridgeService.QUEUE_DESTINATION), any());
    }

    @Test
    void dispatchCompletesWhenResultPosted() {
        service.register(7L, requestWith("computer_task"));
        AtomicReference<String> requestId = new AtomicReference<>();
        doAnswer(invocation -> {
            AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
            requestId.set(push.requestId());
            // 模拟客户端异步回传
            CompletableFuture.runAsync(() ->
                    service.completeResult(7L, push.requestId(), true, "已打开 网易云音乐", null));
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        String result = service.dispatch(7L, "computer_task", "{\"instruction\":\"打开网易云\"}");
        assertThat(result).isEqualTo("已打开 网易云音乐");
        assertThat(requestId.get()).isNotBlank();
    }

    @Test
    void dispatchIncludesCharacterActorFromChatToolContext() {
        ChatToolContext.set(7L, 620L, null, null, "琉璃", "/api/public/files/avatars/x.png");
        try {
            service.register(7L, requestWith("computer_task"));
            AtomicReference<AgentBridgeService.ToolCallPush> pushRef = new AtomicReference<>();
            doAnswer(invocation -> {
                AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
                pushRef.set(push);
                service.completeResult(7L, push.requestId(), true, "ok", null);
                return null;
            }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            service.dispatch(7L, "computer_task", "{}");
            assertThat(pushRef.get().characterId()).isEqualTo(620L);
            assertThat(pushRef.get().characterName()).isEqualTo("琉璃");
            assertThat(pushRef.get().characterAvatarUrl()).isEqualTo("/api/public/files/avatars/x.png");
        } finally {
            ChatToolContext.clear();
        }
    }

    @Test
    void completeResultRejectsWrongUser() {
        service.register(7L, requestWith("computer_task"));
        doAnswer(invocation -> {
            AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
            assertThatThrownBy(() -> service.completeResult(8L, push.requestId(), true, "hack", null))
                    .isInstanceOf(BusinessException.class);
            service.completeResult(7L, push.requestId(), false, null, "被拒绝");
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        String result = service.dispatch(7L, "computer_task", "{}");
        assertThat(result).contains("执行失败").contains("被拒绝");
    }

    @Test
    void unknownToolReturnsFriendlyText() {
        service.register(7L, requestWith("computer_task"));
        assertThat(service.dispatch(7L, "rm_rf", "{}")).contains("没有名为");
    }

    @Test
    void unregisterFailsPendingCalls() {
        service.register(7L, requestWith("computer_task"));
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> service.unregister(7L));
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        String result = service.dispatch(7L, "computer_task", "{}");
        assertThat(result).contains("已断开");
        assertThat(service.isOnline(7L)).isFalse();
    }

    @Test
    void sanitizeStripsControlCharsAndTruncates() {
        assertThat(AgentBridgeService.sanitizeResult("a\u0000b\u0007c\nd")).isEqualTo("abc\nd");
        String longText = "x".repeat(9000);
        String sanitized = AgentBridgeService.sanitizeResult(longText);
        assertThat(sanitized).hasSizeLessThan(8100).contains("已截断");
    }

    @Test
    void heartbeatKeepsSessionAliveAndReportsUnknown() {
        assertThat(service.heartbeat(7L)).isFalse();
        service.register(7L, requestWith("computer_task"));
        assertThat(service.heartbeat(7L)).isTrue();
    }

    @Test
    void clientBridgeToolCallbackExposesDefinitionAndDispatches() {
        service.register(7L, requestWith("computer_task"));
        AgentBridgeService.ClientTool tool = service.availableTools(7L).get(0);
        ClientBridgeToolCallback callback = new ClientBridgeToolCallback(service, 7L, tool);

        assertThat(callback.getToolDefinition().name()).isEqualTo("computer_task");
        assertThat(callback.getToolDefinition().description()).contains("computer_task");
        // 消息通道为 mock 且无人回传 → 超时文案，证明 call 走的是 dispatch
        assertThat(callback.call("{}")).contains("超时");
    }

    @Test
    void clientBridgeToolCallbackKeepsActorAfterContextCleared() {
        ChatToolContext.set(7L, 620L, null, null, "琉璃", "/api/public/files/avatars/x.png");
        service.register(7L, requestWith("computer_task"));
        AgentBridgeService.ClientTool tool = service.availableTools(7L).get(0);
        ClientBridgeToolCallback callback = new ClientBridgeToolCallback(service, 7L, tool);
        ChatToolContext.clear();

        AtomicReference<AgentBridgeService.ToolCallPush> pushRef = new AtomicReference<>();
        doAnswer(invocation -> {
            AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
            pushRef.set(push);
            service.completeResult(7L, push.requestId(), true, "ok", null);
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        assertThat(callback.call("{}")).isEqualTo("ok");
        assertThat(pushRef.get().characterId()).isEqualTo(620L);
        assertThat(pushRef.get().characterName()).isEqualTo("琉璃");
        assertThat(pushRef.get().characterAvatarUrl()).isEqualTo("/api/public/files/avatars/x.png");
    }
}

package com.lianyu.service.tools.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.dto.RegisterAgentToolsRequest;
import com.lianyu.service.tools.ChatToolContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 能力桥：注册/心跳/下发/回传/超时/来源拦截。
 *
 * <p>与旧版的差别：旧版围绕「一个粗粒度 computer_task + 630s 阻塞」，
 * 新版围绕「原子动作 + 按步超时 + 图片与错误码」。`dispatch` 仍保留（deprecated）
 * 以证明旧调用方不会被这次重构打断。
 */
@ExtendWith(MockitoExtension.class)
class AgentBridgeServiceTest {

    private static final String TOOL = "desk_click";

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private AgentBridgeService service;

    @BeforeEach
    void setUp() {
        service = new AgentBridgeService(messagingTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "stepTimeoutMs", 200L);
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

    private AgentBridgeService.ExecRequest exec(String tool, String args) {
        return new AgentBridgeService.ExecRequest(7L, tool, args, 5000L, "R2", "user_request");
    }

    @Test
    void registerThenToolsVisible() {
        service.register(7L, requestWith(TOOL, "desk_window_list"));

        List<AgentBridgeService.ClientTool> tools = service.availableTools(7L);
        assertThat(tools).extracting(AgentBridgeService.ClientTool::name)
                .containsExactly(TOOL, "desk_window_list");
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
    void execWithoutBridgeReturnsOfflineErrorCode() {
        AgentBridgeService.BridgeResult result = service.exec(exec(TOOL, "{}"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("desktop_offline");
        assertThat(result.text()).contains("不在线");
    }

    @Test
    void execTimesOutWithPerStepTimeout() {
        service.register(7L, requestWith(TOOL));

        AgentBridgeService.BridgeResult result = service.exec(exec(TOOL, "{}"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("step_timeout");
        assertThat(result.text()).contains("超时");
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq(AgentBridgeService.QUEUE_DESTINATION), any());
    }

    @Test
    void execCarriesStepMetadataToClient() {
        service.register(7L, requestWith(TOOL));
        AtomicReference<AgentBridgeService.ToolCallPush> pushRef = new AtomicReference<>();
        doAnswer(invocation -> {
            AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
            pushRef.set(push);
            service.completeResult(7L, push.requestId(), true, "ok", null);
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        AgentBridgeService.BridgeResult result = service.exec(
                new AgentBridgeService.ExecRequest(7L, TOOL, "{}", 5000L, "R2", "user_request", 620L, "琉璃", "/a.png"));

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo("ok");
        AgentBridgeService.ToolCallPush push = pushRef.get();
        assertThat(push.name()).isEqualTo(TOOL);
        assertThat(push.timeoutMs()).isEqualTo(200L);      // 被 stepTimeoutMs 封顶
        assertThat(push.danger()).isEqualTo("R2");
        assertThat(push.evidence()).isEqualTo("user_request");
        assertThat(push.characterId()).isEqualTo(620L);
        assertThat(push.characterName()).isEqualTo("琉璃");
    }

    @Test
    void execRefusesHighRiskTriggeredByScreenContent() {
        service.register(7L, requestWith(TOOL));

        AgentBridgeService.BridgeResult result = service.exec(new AgentBridgeService.ExecRequest(
                7L, TOOL, "{}", 5000L, "R3", "screen_content"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("screen_content_high_risk");
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void execKeepsImagesOutOfSanitizedText() {
        service.register(7L, requestWith(TOOL));
        doAnswer(invocation -> {
            AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
            service.completeResult(7L, push.requestId(), true, "点好了", null,
                    List.of(Map.of("mime", "image/jpeg", "b64", "AAAA", "w", 1024, "h", 576,
                            "scaleFactor", 1.5)),
                    Map.of("level", "R2", "confirmed", false));
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        AgentBridgeService.BridgeResult result = service.exec(exec(TOOL, "{}"));

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo("点好了");
        assertThat(result.images()).hasSize(1);
        assertThat(result.images().get(0)).containsEntry("mime", "image/jpeg");
        assertThat(result.policy()).containsEntry("level", "R2");
    }

    @Test
    void execUnknownToolReturnsFriendlyText() {
        service.register(7L, requestWith(TOOL));

        AgentBridgeService.BridgeResult result = service.exec(exec("desk_nope", "{}"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("unknown_tool");
        assertThat(result.text()).contains("没有名为");
    }

    @Test
    void unregisterFailsPendingCalls() {
        service.register(7L, requestWith(TOOL));
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> service.unregister(7L));
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        AgentBridgeService.BridgeResult result = service.exec(exec(TOOL, "{}"));

        assertThat(result.ok()).isFalse();
        assertThat(result.text()).contains("已断开");
        assertThat(service.isOnline(7L)).isFalse();
    }

    @Test
    void completeResultRejectsWrongUser() {
        service.register(7L, requestWith(TOOL));
        doAnswer(invocation -> {
            AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
            assertThatThrownBy(() -> service.completeResult(8L, push.requestId(), true, "hack", null))
                    .isInstanceOf(BusinessException.class);
            service.completeResult(7L, push.requestId(), false, null, "被拒绝");
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        AgentBridgeService.BridgeResult result = service.exec(exec(TOOL, "{}"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("被拒绝");
        assertThat(result.text()).contains("执行失败").contains("被拒绝");
    }

    @Test
    void sanitizeStripsControlCharsAndTruncates() {
        assertThat(AgentBridgeService.sanitizeResult("a\u0000b\u0007c\nd")).isEqualTo("abc\nd");
        String longText = "x".repeat(9000);
        String sanitized = AgentBridgeService.sanitizeResult(longText);
        assertThat(sanitized).hasSizeLessThan(8100).contains("已截断");
    }

    @Test
    void sanitizeImagesDropsBadFrames() {
        List<Map<String, Object>> kept = AgentBridgeService.sanitizeImages(List.of(
                Map.of("mime", "image/jpeg", "b64", "AAA"),
                Map.of("mime", "image/svg+xml", "b64", "BBB"),
                Map.of("mime", "image/png", "b64", ""),
                Map.of("mime", "image/png", "b64", "A".repeat(AgentBridgeService.MAX_IMAGE_B64_CHARS + 1))));

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0)).containsEntry("mime", "image/jpeg");

        assertThat(AgentBridgeService.sanitizeImages(null)).isEmpty();
        assertThat(AgentBridgeService.sanitizeImages(List.of())).isEmpty();
    }

    @Test
    void heartbeatKeepsSessionAliveAndReportsUnknown() {
        assertThat(service.heartbeat(7L)).isFalse();
        service.register(7L, requestWith(TOOL));
        assertThat(service.heartbeat(7L)).isTrue();
    }

    // ───────────────────────── 审计 ─────────────────────────

    @Test
    void execRecordsAuditWithSessionAndStep() {
        CapabilityAuditService audit = mock(CapabilityAuditService.class);
        ReflectionTestUtils.setField(service, "auditService", audit);
        service.register(7L, requestWith(TOOL));
        doAnswer(invocation -> {
            AgentBridgeService.ToolCallPush push = invocation.getArgument(2);
            service.completeResult(7L, push.requestId(), true, "ok", null);
            return null;
        }).when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        AgentBridgeService.BridgeResult result = service.exec(new AgentBridgeService.ExecRequest(
                7L, TOOL, "{\"label\":\"播放\"}", 5000L, "R2", "user_request",
                null, null, null, "desk-7-x", "desk-7-x#2"));

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<CapabilityAuditService.AuditEntry> captor =
                ArgumentCaptor.forClass(CapabilityAuditService.AuditEntry.class);
        verify(audit).record(captor.capture());
        CapabilityAuditService.AuditEntry entry = captor.getValue();
        assertThat(entry.userId()).isEqualTo(7L);
        assertThat(entry.sessionId()).isEqualTo("desk-7-x");
        assertThat(entry.stepId()).isEqualTo("desk-7-x#2");
        assertThat(entry.tool()).isEqualTo(TOOL);
        assertThat(entry.ok()).isTrue();
        assertThat(entry.argsDigest()).hasSize(16);
    }

    @Test
    void refusedAndOfflineAttemptsAreAuditedToo() {
        CapabilityAuditService audit = mock(CapabilityAuditService.class);
        ReflectionTestUtils.setField(service, "auditService", audit);

        AgentBridgeService.BridgeResult offline = service.exec(new AgentBridgeService.ExecRequest(
                7L, TOOL, "{}", 5000L, "R2", "user_request", null, null, null, "s-1", "s-1#1"));
        assertThat(offline.ok()).isFalse();

        service.register(7L, requestWith(TOOL));
        AgentBridgeService.BridgeResult refused = service.exec(new AgentBridgeService.ExecRequest(
                7L, TOOL, "{}", 5000L, "R3", "screen_content", null, null, null, "s-2", "s-2#1"));
        assertThat(refused.errorCode()).isEqualTo("screen_content_high_risk");

        ArgumentCaptor<CapabilityAuditService.AuditEntry> captor =
                ArgumentCaptor.forClass(CapabilityAuditService.AuditEntry.class);
        verify(audit, times(2)).record(captor.capture());
        assertThat(captor.getAllValues().get(0).errorCode()).isEqualTo("desktop_offline");
        assertThat(captor.getAllValues().get(1).errorCode()).isEqualTo("screen_content_high_risk");
        assertThat(captor.getAllValues().get(1).evidence()).isEqualTo("screen_content");
    }
}

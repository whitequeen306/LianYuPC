package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.service.agent.AgentImagePort;
import com.lianyu.service.agent.AgentMemoryPort;
import com.lianyu.service.agent.AgentRuntimePort;
import com.lianyu.service.agent.AgentStreamPort;
import com.lianyu.service.agent.AgentTextPort;
import com.lianyu.service.agent.ChatTurnPort;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 接线自检测试：守「模式说 AGENT，但实际没接线」这类静默故障。
 */
class AgentWiringHealthCheckTest {

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private AgentWiringHealthCheck build(boolean withStream, boolean withText,
                                         boolean withMemory, boolean withImage) {
        return build(withStream, withText, withMemory, withImage, true);
    }

    private AgentWiringHealthCheck build(boolean withStream, boolean withText,
                                         boolean withMemory, boolean withImage,
                                         boolean withChatTurn) {
        return new AgentWiringHealthCheck(
                provider(mock(AgentRuntimePort.class)),
                provider(withStream ? mock(AgentStreamPort.class) : null),
                provider(withText ? mock(AgentTextPort.class) : null),
                provider(withMemory ? mock(AgentMemoryPort.class) : null),
                provider(withImage ? mock(AgentImagePort.class) : null),
                provider(withChatTurn ? mock(ChatTurnPort.class) : null));
    }

    @Test
    void reportsMissingPorts() {
        AgentWiringHealthCheck check = build(false, true, true, true);
        Map<String, Boolean> report = check.inspect();

        assertTrue(check.isAgentMode());
        assertFalse(report.get("stream(AgentStreamPort)"), "缺失必须被报出来");
        assertTrue(report.get("text(AgentTextPort)"));
    }

    @Test
    void reportsMissingChatTurnPort() {
        // 编排整体迁到 Python 后，ChatTurnPort 缺失等于「聊天回合根本没发出去」，
        // 这是最该被启动自检抓到的一类静默故障。
        AgentWiringHealthCheck check = build(true, true, true, true, false);
        assertFalse(check.inspect().get("chatTurn(ChatTurnPort)"));
    }

    @Test
    void allWired() {
        AgentWiringHealthCheck check = build(true, true, true, true);
        assertTrue(check.inspect().values().stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void inspectDoesNotThrowWhenEverythingMissing() {
        AgentWiringHealthCheck check = build(false, false, false, false, false);
        Map<String, Boolean> report = check.inspect();
        assertEquals(6, report.size());
        assertTrue(report.get("proactiveTurn(AgentRuntimePort)"));
        assertEquals(1, report.values().stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void startupCheckIsSafe() {
        build(false, false, false, false, false).checkOnStartup();
        build(true, true, true, true).checkOnStartup();
    }
}

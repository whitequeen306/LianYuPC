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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 接线自检测试。
 *
 * <p>它守的是「模式说 AGENT，但实际没接线」这类静默故障：
 * 第 5 轮 `ChatTurnFacade` 的文本分支被误删，所有单测仍全绿，
 * 因为没人验证调用点存在。
 */
class AgentWiringHealthCheckTest {

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private AgentWiringHealthCheck build(AgentRuntimeProperties.Mode mode,
                                         boolean withStream, boolean withText,
                                         boolean withMemory, boolean withImage) {
        AgentRuntimeClient client = mock(AgentRuntimeClient.class);
        when(client.properties()).thenReturn(new AgentRuntimeProperties(mode, "http://agent:8001", true));
        return new AgentWiringHealthCheck(
                client,
                provider(mock(AgentRuntimePort.class)),
                provider(withStream ? mock(AgentStreamPort.class) : null),
                provider(withText ? mock(AgentTextPort.class) : null),
                provider(withMemory ? mock(AgentMemoryPort.class) : null),
                provider(withImage ? mock(AgentImagePort.class) : null));
    }

    @Test
    void reportsMissingPortsInAgentMode() {
        AgentWiringHealthCheck check = build(AgentRuntimeProperties.Mode.AGENT,
                false, true, true, true);
        Map<String, Boolean> report = check.inspect();

        assertTrue(check.isAgentMode());
        assertFalse(report.get("stream(AgentStreamPort)"), "缺失必须被报出来");
        assertTrue(report.get("text(AgentTextPort)"));
    }

    @Test
    void allWiredInAgentMode() {
        AgentWiringHealthCheck check = build(AgentRuntimeProperties.Mode.AGENT,
                true, true, true, true);
        assertTrue(check.inspect().values().stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void notAgentModeInOffOrShadow() {
        assertFalse(build(AgentRuntimeProperties.Mode.OFF, true, true, true, true).isAgentMode());
        assertFalse(build(AgentRuntimeProperties.Mode.SHADOW, true, true, true, true).isAgentMode());
    }

    @Test
    void inspectDoesNotThrowWhenEverythingMissing() {
        AgentWiringHealthCheck check = build(AgentRuntimeProperties.Mode.AGENT,
                false, false, false, false);
        Map<String, Boolean> report = check.inspect();
        assertEquals(5, report.size());
        // runtimePort 始终存在（本测试固定注入），其余四个应全为缺失
        assertTrue(report.get("proactiveTurn(AgentRuntimePort)"));
        assertEquals(1, report.values().stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void startupCheckIsSafeInAnyMode() {
        // 不抛异常即可（日志分支由上面断言覆盖）
        build(AgentRuntimeProperties.Mode.SHADOW, false, false, false, false).checkOnStartup();
        build(AgentRuntimeProperties.Mode.AGENT, true, true, true, true).checkOnStartup();
        build(AgentRuntimeProperties.Mode.AGENT, false, false, false, false).checkOnStartup();
    }
}

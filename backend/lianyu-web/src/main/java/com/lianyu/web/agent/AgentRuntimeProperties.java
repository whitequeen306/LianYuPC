package com.lianyu.web.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * backend-agent（FastAPI/LangGraph）运行时对接配置。
 *
 * <p>Strangler 灰度开关（docs/refactor-2026-10/refactor-plan.md §3.4）：Java 仍是唯一对外入口，
 * 按模式把 AI 链路转发到 Python 侧。
 */
@ConfigurationProperties(prefix = "lianyu.agent")
public record AgentRuntimeProperties(
        /** off=纯 Java；agent=Python 出结果、Java 兜底 */
        Mode mode,
        /** 容器内地址（compose service agent），仅内网可信 */
        String baseUrl) {

    public enum Mode { OFF, AGENT }

    public AgentRuntimeProperties {
        if (mode == null) {
            mode = Mode.OFF;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://agent:8001";
        }
    }

    public boolean agentActive() {
        return mode == Mode.AGENT;
    }
}

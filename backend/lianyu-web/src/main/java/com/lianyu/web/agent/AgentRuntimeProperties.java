package com.lianyu.web.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * backend-agent（FastAPI/LangGraph）运行时对接配置。
 *
 * <p>OFF 退役后只有一条运行路径：所有 AI 调用都走 Python，Java 保留事务/落库/加解密。
 * `baseUrl` 是容器内地址（compose service agent），仅内网可信。
 */
@ConfigurationProperties(prefix = "lianyu.agent")
public record AgentRuntimeProperties(String baseUrl) {

    public AgentRuntimeProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://agent:8001";
        }
    }

    /** 兼容旧调用点：OFF 已退役，运行时始终启用 agent。 */
    public boolean agentActive() {
        return true;
    }
}

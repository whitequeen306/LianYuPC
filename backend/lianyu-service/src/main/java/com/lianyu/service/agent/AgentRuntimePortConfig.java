package com.lianyu.service.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 运行时端口装配：web 层（lianyu-web 的 DesktopObserveOrchestrator）提供真实实现；
 * 单测或裁剪部署无 web 层时回落 Noop（OFF 语义）。
 */
@Configuration
public class AgentRuntimePortConfig {

    @Bean
    @ConditionalOnMissingBean(AgentRuntimePort.class)
    public AgentRuntimePort noopAgentRuntimePort() {
        return new NoopAgentRuntimePort();
    }
}

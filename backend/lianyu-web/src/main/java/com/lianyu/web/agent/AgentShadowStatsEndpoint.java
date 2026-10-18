package com.lianyu.web.agent;

import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * 漂移率只读端点：`GET /actuator/agentshadow`。
 *
 * <p>为什么用 actuator 而不是自己写 Controller：
 * 鉴权/暴露策略由 actuator 统一管理（默认不暴露，需显式 include），
 * 不需要在家里的 `/api/**` 上再开一个可能被公网扫到的口子。
 *
 * <p>暴露内容只有计数与百分比，不含用户数据、不含 prompt。
 */
@Component
@Endpoint(id = "agentshadow")
public class AgentShadowStatsEndpoint {

    private final AgentShadowStats stats;

    public AgentShadowStatsEndpoint(AgentShadowStats stats) {
        this.stats = stats;
    }

    @ReadOperation
    public Map<String, Object> shadow() {
        Map<String, String> snapshot = stats.snapshot();
        return Map.of(
                "channels", snapshot,
                "hint", "drift = diff / (match + diff)，不含 both-null；"
                        + "仅 SHADOW 模式产生样本"
        );
    }
}

package com.lianyu.web.agent;

import com.lianyu.service.agent.AssemblySnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
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

    private final AgentRuntimeClient client;

    public AgentShadowStatsEndpoint(AgentShadowStats stats, AgentRuntimeClient client) {
        this.stats = stats;
        this.client = client;
    }

    /**
     * 接线自检：真实调用一次 Python 的 `/agent/prompt/assemble`，确认
     * 「Java → agent → 组装结果」这条链路在容器内可用。
     *
     * <p>**不是漂移检测**：它只证明链路通、Python 能返回结构完整的 prompt。
     * 真实漂移必须在 SHADOW 模式下由真实对话触发（那时才有 Java 侧基准）。
     */
    @WriteOperation
    public Map<String, Object> plumbingCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            AssemblySnapshot snapshot = new AssemblySnapshot(
                    "影子自检", Map.of("personality", "温柔"), null,
                    "", "", "", "zh", false, true, 2, false, null, null, "none");
            String response = client.postJson("/agent/prompt/assemble", 0L,
                    PromptShadowComparator.bodyOf(snapshot, 0L));
            if (response == null) {
                result.put("reachable", false);
                result.put("reason", "no response (agent down or timeout)");
                return result;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                result.put("reachable", false);
                result.put("reason", root.path("error").asText());
                return result;
            }
            String prompt = root.path("systemPrompt").asText("");
            result.put("reachable", true);
            result.put("chars", prompt.length());
            result.put("hasGuard", prompt.contains("安全与边界"));
            result.put("hasReplyRules", prompt.contains("回复规则："));
            result.put("hasPersona", prompt.contains("影子自检"));
            return result;
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("reason", e.toString());
            return result;
        }
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

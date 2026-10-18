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
    private final com.lianyu.service.graph.ChatTurnPromptAssembler promptAssembler;
    private final PromptShadowComparator comparator;

    public AgentShadowStatsEndpoint(AgentShadowStats stats,
                                    AgentRuntimeClient client,
                                    com.lianyu.service.graph.ChatTurnPromptAssembler promptAssembler,
                                    PromptShadowComparator comparator) {
        this.stats = stats;
        this.client = client;
        this.promptAssembler = promptAssembler;
        this.comparator = comparator;
    }

    /**
     * 跨语言一致性自检：**真实调用 Java 的 assemble()**，再把同样的输入发给 Python，
     * 比对两边产出的 system prompt。
     *
     * <p>与 `plumbingCheck` 的区别：那个只证明「Python 能返回 prompt」，这个证明
     * 「Java 与 Python 组装出来的**内容**一致」。用固定角色 fixture、空记忆/关系块，
     * 所以它验的是**组装逻辑**，不含真实数据 —— 真实数据仍需 SHADOW 下跑真实对话。
     */
    @WriteOperation
    public Map<String, Object> plumbingCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            com.lianyu.dao.entity.Character character = new com.lianyu.dao.entity.Character();
            character.setId(999_999L);
            character.setName("影子自检");
            character.setSettings(new LinkedHashMap<>(Map.of("personality", "温柔但有点固执")));

            com.lianyu.service.graph.ChatTurnPromptAssembler.AssembledPrompt assembled =
                    promptAssembler.assemble(com.lianyu.ai.graph.ChatTurnScene.MOMENTS,
                            0L, null, character, "", "", "", null);
            String javaPrompt = assembled.systemPrompt();

            String memoryContext = String.join("\n\n",
                    java.util.stream.Stream.of(assembled.memoryBlock(),
                                    assembled.relationshipBlock(), assembled.sessionSummaryBlock())
                            .filter(s -> s != null && !s.isBlank())
                            .toList());

            AssemblySnapshot snapshot = new AssemblySnapshot(
                    character.getName(), character.getSettings(), null,
                    memoryContext, "", "", assembled.outputLanguage(),
                    false, true, 2, false, null, null, "style_only");

            String response = client.postJson("/agent/prompt/assemble", 0L,
                    PromptShadowComparator.bodyOf(snapshot, 0L));
            if (response == null) {
                result.put("reachable", false);
                result.put("reason", "no response from agent");
                return result;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                result.put("reachable", false);
                result.put("reason", root.path("error").asText());
                return result;
            }
            String pythonPrompt = root.path("systemPrompt").asText("");
            PromptShadowComparator.Diff diff =
                    PromptShadowComparator.compare(javaPrompt, pythonPrompt);
            if (diff.equal()) {
                stats.recordMatch("prompt");
            } else {
                stats.recordDiff("prompt");
            }
            result.put("reachable", true);
            result.put("equal", diff.equal());
            result.put("javaChars", diff.javaLength());
            result.put("pythonChars", diff.pythonLength());
            result.put("firstDiffAt", diff.firstDiffAt());
            result.put("channelStats", stats.snapshot());
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

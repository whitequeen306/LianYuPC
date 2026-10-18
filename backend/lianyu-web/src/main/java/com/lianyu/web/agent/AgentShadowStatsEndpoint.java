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

    /** 影子自检固定使用 user 7 + character 999999（种子数据见 P5.6 文档）。 */
    private static final long FIXTURE_USER_ID = 7L;

    private final AgentShadowStats stats;

    private final AgentRuntimeClient client;
    private final com.lianyu.service.graph.ChatTurnPromptAssembler promptAssembler;
    private final PromptShadowComparator comparator;
    private final com.lianyu.service.tools.TimeTool timeTool;
    private final com.lianyu.service.tools.ToolManager toolManager;

    public AgentShadowStatsEndpoint(AgentShadowStats stats,
                                    AgentRuntimeClient client,
                                    com.lianyu.service.graph.ChatTurnPromptAssembler promptAssembler,
                                    PromptShadowComparator comparator,
                                    com.lianyu.service.tools.TimeTool timeTool,
                                    com.lianyu.service.tools.ToolManager toolManager) {
        this.stats = stats;
        this.client = client;
        this.promptAssembler = promptAssembler;
        this.comparator = comparator;
        this.timeTool = timeTool;
        this.toolManager = toolManager;
    }

    /**
     * 跨语言一致性自检：**真实调用 Java 的 assemble()**，再把同样的输入发给 Python，
     * 比对两边产出的 system prompt。
     *
     * <p>与 `plumbingCheck` 的区别：那个只证明「Python 能返回 prompt」，这个证明
     * 「Java 与 Python 组装出来的**内容**一致」。用固定角色 fixture、空记忆/关系块，
     * 所以它验的是**组装逻辑**，不含真实数据 —— 真实数据仍需 SHADOW 下跑真实对话。
     */
    /**
     * 跨语言一致性自检：覆盖多个场景分支的真实比对。
     *
     * <p>Python 侧 useInternalData=true，**自己查库**读记忆/关系/情绪/摘要；
     * Java 侧走真实服务。三个场景分别覆盖：
     * <ul>
     *   <li>MOMENTS：style_only（关系 + 记忆，无时间/城市）</li>
     *   <li>VOICE_CALL：none（仅时间锚点，无记忆/关系，且关闭内心独白）</li>
     *   <li>SINGLE：full（时间 + 城市 + 记忆 + 关系 + 摘要 + 工具提示）</li>
     * </ul>
     *
     * <p>已知缺口：SINGLE 的 toolHint 仍由 Java 计算后传入 —— 工具清单属于 Java 侧
     * 的角色配置，Python 尚未读取。其余块全部由 Python 自读。
     */
    @WriteOperation
    public Map<String, Object> plumbingCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> scenes = new LinkedHashMap<>();
        try {
            com.lianyu.dao.entity.Character character = fixtureCharacter();

            for (com.lianyu.ai.graph.ChatTurnScene scene : new com.lianyu.ai.graph.ChatTurnScene[]{
                    com.lianyu.ai.graph.ChatTurnScene.MOMENTS,
                    com.lianyu.ai.graph.ChatTurnScene.VOICE_CALL,
                    com.lianyu.ai.graph.ChatTurnScene.SINGLE}) {
                scenes.put(scene.name(), checkScene(scene, character));
            }
            result.put("scenes", scenes);
            return result;
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("reason", e.toString());
            return result;
        }
    }

    private com.lianyu.dao.entity.Character fixtureCharacter() {
        com.lianyu.dao.entity.Character character = new com.lianyu.dao.entity.Character();
        character.setId(999_999L);
        character.setOwnerUserId(FIXTURE_USER_ID);
        character.setName("影子自检");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("personality", "温柔但有点固执");
        settings.put("city_mode", "real");
        settings.put("city", "上海");
        character.setSettings(settings);
        return character;
    }

    private Map<String, Object> checkScene(com.lianyu.ai.graph.ChatTurnScene scene,
                                           com.lianyu.dao.entity.Character character) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            com.lianyu.service.graph.ChatTurnPromptAssembler.AssembledPrompt assembled =
                    promptAssembler.assemble(scene, FIXTURE_USER_ID, null, character,
                            "", "", "", null);
            String javaPrompt = assembled.systemPrompt();

            boolean full = scene == com.lianyu.ai.graph.ChatTurnScene.SINGLE
                    || scene == com.lianyu.ai.graph.ChatTurnScene.PROACTIVE;
            boolean styleOnly = scene == com.lianyu.ai.graph.ChatTurnScene.MOMENTS
                    || scene == com.lianyu.ai.graph.ChatTurnScene.DIARY;
            String afterBase = full ? "full"
                    : (styleOnly ? "style_only"
                    : (scene == com.lianyu.ai.graph.ChatTurnScene.VOICE_CALL ? "time_only" : "none"));
            boolean includeTime = full || scene == com.lianyu.ai.graph.ChatTurnScene.VOICE_CALL;
            boolean tools = scene.enableChatTools();
            boolean inner = scene != com.lianyu.ai.graph.ChatTurnScene.VOICE_CALL;
            String city = full ? String.valueOf(character.getSettings().get("city")) : null;
            String timeFact = includeTime && timeTool != null
                    ? timeTool.readCurrentTimeFact() : null;
            // 工具提示依赖角色的工具配置（Java 侧），Python 尚未读取 —— 由 Java 计算后传入
            String toolHint = tools && toolManager != null
                    ? toolManager.buildToolsPromptHint(FIXTURE_USER_ID) : "";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", FIXTURE_USER_ID);
            body.put("characterName", character.getName());
            body.put("settings", character.getSettings());
            body.put("promptTemplate", null);
            body.put("memoryContext", "");
            body.put("toolHint", toolHint);
            body.put("emotionBlock", "");
            body.put("outputLanguage", assembled.outputLanguage());
            body.put("enableChatTools", tools);
            body.put("showInnerThoughts", inner);
            body.put("maxPieces", 2);
            body.put("includeTime", includeTime);
            body.put("timeFact", timeFact);
            body.put("city", city);
            body.put("afterBase", afterBase);
            body.put("useInternalData", true);
            body.put("scene", scene.name());
            body.put("characterId", character.getId());

            String response = client.postJson("/agent/prompt/assemble", FIXTURE_USER_ID, body);
            if (response == null) {
                out.put("reachable", false);
                return out;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                out.put("reachable", false);
                out.put("reason", root.path("error").asText());
                return out;
            }
            String pythonPrompt = root.path("systemPrompt").asText("");
            PromptShadowComparator.Diff diff =
                    PromptShadowComparator.compare(javaPrompt, pythonPrompt);
            if (diff.equal()) {
                stats.recordMatch("prompt");
            } else {
                stats.recordDiff("prompt");
            }
            out.put("reachable", true);
            out.put("equal", diff.equal());
            out.put("javaChars", diff.javaLength());
            out.put("pythonChars", diff.pythonLength());
            out.put("firstDiffAt", diff.firstDiffAt());
            java.util.List<String> segments = new java.util.ArrayList<>();
            for (PromptShadowComparator.SegmentDiff segment
                    : PromptShadowComparator.segmentDiff(javaPrompt, pythonPrompt)) {
                segments.add(segment.describe());
            }
            out.put("segments", segments);
            return out;
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("reason", e.toString());
            return out;
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

package com.lianyu.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * prompt 组装的输入快照（字段名与 Python `/agent/prompt/assemble` 的 alias 一一对应）。
 *
 * <p>放在 service 层是因为它由 {@code ChatTurnFacade} 产出、由 web 层消费；
 * 内容全部是「已经取好的数据块」，不含任何服务引用 —— 这样 Python 侧只需拼装，
 * 不必重复实现记忆/关系/情绪/摘要的取数。
 *
 * @param characterName    角色名
 * @param settings         角色 settings（persona/城市等）
 * @param promptTemplate   角色自定义模板，可为 null
 * @param memoryContext    记忆 + 关系 + 会话摘要三块用 {@code \n\n} 连接后的结果
 * @param toolHint         工具提示串（关闭工具时为空）
 * @param emotionBlock     情绪块（无情绪时为空）
 * @param outputLanguage   语言代码
 * @param enableChatTools  场景是否启用工具
 * @param showInnerThoughts 是否允许括号内心独白
 * @param maxPieces        单轮最多气泡数
 * @param includeTime      是否追加时间锚点
 * @param timeFact         时间事实串（includeTime 为 false 时可空）
 * @param city             现实城市（null 表示不追加城市段）
 * @param afterBase        full / style_only / none（对应 Java assemble 的三个分支）
 */
public record AssemblySnapshot(
        String characterName,
        Map<String, Object> settings,
        String promptTemplate,
        String memoryContext,
        String toolHint,
        String emotionBlock,
        String outputLanguage,
        boolean enableChatTools,
        boolean showInnerThoughts,
        int maxPieces,
        boolean includeTime,
        String timeFact,
        String city,
        String afterBase,
        boolean useInternalData,
        String scene,
        Long characterId,
        Long conversationId,
        Long characterOwnerId,
        java.util.Map<String, Object> toolFlags,
        String lastUserMessage,
        String sessionSummary) {

    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("characterName", characterName == null ? "" : characterName);
        body.put("settings", settings == null ? Map.of() : settings);
        body.put("promptTemplate", promptTemplate);
        body.put("memoryContext", memoryContext == null ? "" : memoryContext);
        body.put("toolHint", toolHint == null ? "" : toolHint);
        if (characterOwnerId != null) {
            body.put("characterOwnerId", characterOwnerId);
        }
        if (toolFlags != null) {
            body.put("toolFlags", toolFlags);
        }
        body.put("emotionBlock", emotionBlock == null ? "" : emotionBlock);
        body.put("outputLanguage", outputLanguage == null ? "zh" : outputLanguage);
        body.put("enableChatTools", enableChatTools);
        body.put("showInnerThoughts", showInnerThoughts);
        body.put("maxPieces", maxPieces);
        body.put("includeTime", includeTime);
        body.put("timeFact", timeFact);
        body.put("city", city);
        body.put("afterBase", afterBase == null ? "full" : afterBase);
        body.put("useInternalData", useInternalData);
        if (scene != null) {
            body.put("scene", scene);
        }
        if (characterId != null) {
            body.put("characterId", characterId);
        }
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        return body;
    }
}

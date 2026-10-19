package com.lianyu.service.agent;

/**
 * agent 组装请求（字段与 Python `/agent/prompt/assemble` 对齐）。
 *
 * @param userId         请求用户（记忆/关系维度）
 * @param characterId    角色 id
 * @param conversationId 会话 id（摘要维度），可为 null
 * @param characterName  角色名
 * @param settings       角色 settings（人设/城市）
 * @param promptTemplate 角色自定义模板，可为 null
 * @param scene          场景名（SINGLE/GROUP/MOMENTS/DIARY/PROACTIVE/VOICE_CALL）
 * @param outputLanguage 输出语言
 * @param afterBase      full / style_only / time_only / none
 * @param includeTime    是否追加时间锚点
 * @param timeFact       时间事实串（null 时由 Python 自行读取）
 * @param enableChatTools 场景是否启用工具
 * @param showInnerThoughts 是否允许括号内心独白
 * @param maxPieces      单轮最多气泡数
 * @param toolHint       工具提示（依赖 Java 运行时桌面桥接状态）
 */
public record PromptAssemblyRequest(
        long userId,
        Long characterId,
        Long conversationId,
        String characterName,
        java.util.Map<String, Object> settings,
        String promptTemplate,
        String scene,
        String outputLanguage,
        String afterBase,
        boolean includeTime,
        String timeFact,
        boolean enableChatTools,
        boolean showInnerThoughts,
        int maxPieces,
        String toolHint,
        java.util.Map<String, Object> toolFlags) {
}

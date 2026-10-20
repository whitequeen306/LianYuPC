package com.lianyu.service.agent;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * 一次聊天回合的**原始输入**（不含任何 prompt 文本或消息拼装结果）。
 *
 * <p>编排已整体迁到 Python 的 LangGraph，Java 只负责把「事实」交给它：
 * 场景名、角色设定、历史消息、开关、以及解密后的模型凭据。
 * 这里刻意不接受 systemPrompt / messages —— 一旦接受，组装逻辑就会悄悄回到 Java。
 *
 * <p>字段分三类：
 * <ol>
 *   <li>身份事实：userId / conversationId / characterId / characterName</li>
 *   <li>本轮文本：userText（喂模型，可含识图描述）、rawUserText（用户原文，用于记忆检索与语言判定）</li>
 *   <li>场景开关：由 {@code ChatTurnScene} 派生，Python 侧同名字段可覆盖</li>
 * </ol>
 */
@Value
@Builder
public class ChatTurnRequest {

    // ── 身份事实 ──
    long userId;
    Long conversationId;
    Long characterId;
    String characterName;
    Map<String, Object> settings;
    String promptTemplate;

    /** 场景名：SINGLE / GROUP / MOMENTS / DIARY / PROACTIVE / VOICE_CALL */
    String scene;
    String outputLanguage;

    // ── 本轮文本 ──
    String userText;
    String rawUserText;

    // ── 历史与预置消息 ──
    /** 历史消息（id/role/content/contextContent/imageUrl/audioUrl），拼装在 Python 内完成 */
    List<Map<String, Object>> history;
    /** 群聊主动发言：调用方已备好的完整消息列表 */
    List<Map<String, Object>> preparedMessages;
    Long currentUserMessageId;

    // ── 场景开关与数据块 ──
    String timeFact;
    Map<String, Object> toolFlags;
    boolean enableChatTools;
    boolean showInnerThoughts;
    int maxPieces;
    int groupMaxPieces;
    String groupOtherCharacters;
    String groupMention;
    /** 调用方动态追加到 system 末尾的块（Java 不再自己拼任何 prompt 文本） */
    String extraSystemSuffix;
    boolean prefetchRealWorld;

    // ── 模型与凭据（由 web 侧适配器解析 vault 后填入） ──
    String provider;
    String model;
    Double temperature;
    Integer maxTokens;
    /** 期望输出语言（语言门；null 表示不设门） */
    String expectedLanguage;
}

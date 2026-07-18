package com.lianyu.ai.graph;

/**
 * ChatTurn Graph 的 OverAllState 键契约。业务节点禁止使用裸字符串 key。
 */
public final class ChatTurnKeys {

    private ChatTurnKeys() {
    }

    // --- inputs ---
    public static final String USER_ID = "userId";
    public static final String CHARACTER_ID = "characterId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String RAW_USER_TEXT = "rawUserText";
    public static final String MODEL_USER_TEXT = "modelUserText";
    public static final String PROVIDER = "provider";
    public static final String MODEL = "model";
    public static final String TEMPERATURE = "temperature";
    public static final String OUTPUT_LANGUAGE = "outputLanguage";
    public static final String SCENE = "scene";
    public static final String IMAGE_URL = "imageUrl";
    public static final String HISTORY_MESSAGES = "historyMessages";
    public static final String STREAMING = "streaming";
    public static final String STREAM_SINK = "streamSink";
    public static final String CHARACTER_REF = "characterRef";
    public static final String TOOL_CHARACTER_SETTINGS = "toolCharacterSettings";
    public static final String USER_CITY = "userCity";
    public static final String EXTRA_SYSTEM_SUFFIX = "extraSystemSuffix";
    public static final String CURRENT_USER_MSG_ID = "currentUserMsgId";
    public static final String GROUP_EXTRAS = "groupExtras";
    public static final String PREPARED_MESSAGES = "preparedMessages";

    // --- assembly blocks ---
    public static final String MEMORY_BLOCK = "memoryBlock";
    public static final String RELATIONSHIP_BLOCK = "relationshipBlock";
    public static final String SESSION_SUMMARY_BLOCK = "sessionSummaryBlock";
    public static final String SYSTEM_PROMPT = "systemPrompt";
    public static final String MESSAGE_DTOS = "messageDtos";

    // --- model outputs ---
    public static final String ASSISTANT_RAW = "assistantRaw";
    public static final String ASSISTANT_FINAL = "assistantFinal";
    public static final String TOTAL_TOKENS = "totalTokens";
    public static final String PROMPT_TOKENS = "promptTokens";
    public static final String COMPLETION_TOKENS = "completionTokens";
    public static final String INVOKE_ERROR = "invokeError";
}

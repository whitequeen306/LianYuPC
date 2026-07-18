package com.lianyu.ai.graph;

import com.alibaba.cloud.ai.graph.OverAllState;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OverAllState 的领域读写封装，避免业务节点散落魔法字符串。
 */
public final class ChatTurnState {

    private final OverAllState raw;

    public ChatTurnState(OverAllState raw) {
        this.raw = raw;
    }

    public OverAllState raw() {
        return raw;
    }

    public Long userId() {
        return raw.value(ChatTurnKeys.USER_ID, Long.class).orElse(null);
    }

    public Long characterId() {
        return raw.value(ChatTurnKeys.CHARACTER_ID, Long.class).orElse(null);
    }

    public Long conversationId() {
        return raw.value(ChatTurnKeys.CONVERSATION_ID, Long.class).orElse(null);
    }

    public ChatTurnScene scene() {
        return raw.value(ChatTurnKeys.SCENE, ChatTurnScene.class).orElse(ChatTurnScene.SINGLE);
    }

    public String outputLanguage() {
        return raw.value(ChatTurnKeys.OUTPUT_LANGUAGE, String.class).orElse("zh");
    }

    public String rawUserText() {
        return raw.value(ChatTurnKeys.RAW_USER_TEXT, String.class).orElse("");
    }

    public String modelUserText() {
        return raw.value(ChatTurnKeys.MODEL_USER_TEXT, String.class).orElse("");
    }

    public String imageUrl() {
        return raw.value(ChatTurnKeys.IMAGE_URL, String.class).orElse(null);
    }

    public boolean streaming() {
        return Boolean.TRUE.equals(raw.value(ChatTurnKeys.STREAMING, Boolean.class).orElse(Boolean.FALSE));
    }

    public Optional<StreamTokenConsumer> streamSink() {
        return raw.value(ChatTurnKeys.STREAM_SINK, StreamTokenConsumer.class);
    }

    public String systemPrompt() {
        return raw.value(ChatTurnKeys.SYSTEM_PROMPT, String.class).orElse("");
    }

    public String assistantFinal() {
        return raw.value(ChatTurnKeys.ASSISTANT_FINAL, String.class).orElse("");
    }

    public String memoryBlock() {
        return raw.value(ChatTurnKeys.MEMORY_BLOCK, String.class).orElse("");
    }

    public String extraSystemSuffix() {
        return raw.value(ChatTurnKeys.EXTRA_SYSTEM_SUFFIX, String.class).orElse("");
    }

    @SuppressWarnings("unchecked")
    public List<Object> historyMessages() {
        return raw.value(ChatTurnKeys.HISTORY_MESSAGES, List.class).orElse(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    public List<Object> messageDtos() {
        return raw.value(ChatTurnKeys.MESSAGE_DTOS, List.class).orElse(Collections.emptyList());
    }

    public Object characterRef() {
        return raw.value(ChatTurnKeys.CHARACTER_REF).orElse(null);
    }

    public static Map<String, Object> put(String key, Object value) {
        Map<String, Object> m = new HashMap<>(1);
        m.put(key, value);
        return m;
    }

    public static Map<String, Object> putAll(Map<String, Object> updates) {
        return updates == null ? Map.of() : updates;
    }
}

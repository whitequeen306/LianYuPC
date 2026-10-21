package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Pet VC voice registry — HTTP and realtime voiceIds are separate enrollments.
 *
 * <ul>
 *   <li>{@code voices} + {@code model}: HTTP multimodal TTS (desktop pet / cold-open)</li>
 *   <li>{@code realtimeVoices} + {@code realtimeModel}: Realtime WS TTS (voice call)</li>
 * </ul>
 */
@Slf4j
@Component
public class PetVoiceRegistry {

    private final ObjectMapper objectMapper;

    /**
     * 复刻音色的合成模型。
     *
     * <p>2026-10-21 起是 {@code qwen-audio-3.1-tts-flash}：旧的 {@code qwen3-tts-vc-*}
     * 系列已下架（合成报 {@code Model not exist}），而 {@code qwen3-tts-flash} 虽然可用
     * 却不支持声音复刻。13 个角色音色已按新模型重新注册，见 {@code pet-voices.json}。
     */
    @Getter
    private String model = "qwen-audio-3.1-tts-flash";

    /**
     * 实时复刻音色模型 —— 平台已无此能力，保持空串。
     *
     * <p>{@code qwen3-tts-flash-realtime} 能流式出音频但只支持系统音色；
     * {@code qwen-audio-*-realtime-*} 是语音对话模型，不是 TTS。所以
     * 「实时 + 复刻」这个组合已不存在，{@code realtimeVoices} 也随之清空；
     * 语音通话改由 {@link #model} 逐句合成。
     */
    @Getter
    private String realtimeModel = "";

    /** petId → HTTP voiceId */
    private Map<String, String> httpVoices = Collections.emptyMap();

    /** petId → realtime voiceId (never fall back to HTTP id) */
    private Map<String, String> realtimeVoices = Collections.emptyMap();

    public PetVoiceRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("pet-voices.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            if (root.hasNonNull("model")) {
                model = root.get("model").asText(model);
            }
            if (root.hasNonNull("realtimeModel")) {
                realtimeModel = root.get("realtimeModel").asText(realtimeModel);
            }
            httpVoices = Collections.unmodifiableMap(readVoiceMap(root.get("voices")));
            realtimeVoices = Collections.unmodifiableMap(readVoiceMap(root.get("realtimeVoices")));
            log.info("Loaded pet voices: http={} model={} realtime={} realtimeModel={}",
                    httpVoices.size(), model, realtimeVoices.size(), realtimeModel);
        } catch (Exception e) {
            log.warn("pet-voices.json not loaded, pet TTS disabled: {}", e.getMessage());
            httpVoices = Collections.emptyMap();
            realtimeVoices = Collections.emptyMap();
        }
    }

    private static Map<String, String> readVoiceMap(JsonNode node) {
        Map<String, String> loaded = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    loaded.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue().asText()));
        }
        return loaded;
    }

    /** HTTP voiceId for desktop pet / cold-open / HTTP synthesis. */
    public String resolveHttpVoiceId(String petId) {
        return lookup(httpVoices, petId);
    }

    /**
     * Realtime voiceId for voice-call streaming TTS.
     * Does not fall back to HTTP id (wrong target_model → CLIENT_ERROR).
     */
    public String resolveRealtimeVoiceId(String petId) {
        return lookup(realtimeVoices, petId);
    }

    /** @deprecated prefer {@link #resolveHttpVoiceId(String)} — kept for existing callers */
    public String resolveVoiceId(String petId) {
        return resolveHttpVoiceId(petId);
    }

    public boolean hasVoice(String petId) {
        return resolveHttpVoiceId(petId) != null;
    }

    public boolean hasRealtimeVoice(String petId) {
        return resolveRealtimeVoiceId(petId) != null;
    }

    private static String lookup(Map<String, String> map, String petId) {
        if (petId == null || petId.isBlank()) {
            return null;
        }
        return map.get(petId.trim().toLowerCase(java.util.Locale.ROOT));
    }
}

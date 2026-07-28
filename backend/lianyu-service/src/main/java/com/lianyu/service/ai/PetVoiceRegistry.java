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

    /** HTTP VC model (qwen3-tts-vc-*). */
    @Getter
    private String model = "qwen3-tts-vc-2026-01-22";

    /** Realtime VC model (qwen3-tts-vc-realtime-*). */
    @Getter
    private String realtimeModel = "qwen3-tts-vc-realtime-2026-01-15";

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

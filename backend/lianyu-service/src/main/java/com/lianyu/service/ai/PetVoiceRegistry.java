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
     * <p>2026-10-22 起是 {@code cosyvoice-v3.5-flash}：旧的 {@code qwen3-tts-vc-*} 系列已下架
     * （合成报 {@code Model not exist}），{@code qwen3-tts-flash} 虽然可用却不支持声音复刻，
     * {@code qwen-audio-3.1-tts-flash} 生产账号未开通。13 个角色音色已按新模型重新注册，
     * 见 {@code pet-voices.json}。
     *
     * <p>⚠️ 复刻音色<b>绑定 target_model，不能跨模型</b>：只改这里而不重新注册音色，
     * 合成会报 {@code Engine return error code: 418}。
     */
    @Getter
    private String model = "cosyvoice-v3.5-flash";

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

    /** petId → 语速倍率（1.0 = 模型默认）；只给听感偏慢的角色配。 */
    private Map<String, Double> rates = Collections.emptyMap();

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
            rates = Collections.unmodifiableMap(readRateMap(root.get("rates")));
            log.info("Loaded pet voices: http={} model={} realtime={} realtimeModel={}",
                    httpVoices.size(), model, realtimeVoices.size(), realtimeModel);
        } catch (Exception e) {
            log.warn("pet-voices.json not loaded, pet TTS disabled: {}", e.getMessage());
            httpVoices = Collections.emptyMap();
            realtimeVoices = Collections.emptyMap();
            rates = Collections.emptyMap();
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

    private static Map<String, Double> readRateMap(JsonNode node) {
        Map<String, Double> loaded = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNumber()) {
                    loaded.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue().asDouble());
                }
            });
        }
        return loaded;
    }

    /**
     * 该音色的语速倍率；没配置时返回 null（= 用模型默认 1.0）。
     *
     * <p>为什么按音色而不是全局：TTS 音色的默认语感差异很大，全局调快会把
     * 本来正常的角色说过头；只给听感偏慢的（例如雷电将军）单独调快即可。
     */
    public Double getRate(String petId) {
        if (petId == null || petId.isBlank()) {
            return null;
        }
        return rates.get(petId.trim().toLowerCase(java.util.Locale.ROOT));
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

package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PetVoiceRegistry {

    private final ObjectMapper objectMapper;

    @Getter
    private String model = "qwen3-tts-vc-2026-01-22";

    private Map<String, String> voices = Collections.emptyMap();

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
            Map<String, String> loaded = new HashMap<>();
            JsonNode voiceNode = root.get("voices");
            if (voiceNode != null && voiceNode.isObject()) {
                voiceNode.fields().forEachRemaining(entry ->
                        loaded.put(entry.getKey(), entry.getValue().asText()));
            }
            voices = Collections.unmodifiableMap(loaded);
            log.info("Loaded {} pet voice mappings for model {}", voices.size(), model);
        } catch (Exception e) {
            log.warn("pet-voices.json not loaded, pet TTS disabled: {}", e.getMessage());
            voices = Collections.emptyMap();
        }
    }

    public String resolveVoiceId(String petId) {
        if (petId == null || petId.isBlank()) {
            return null;
        }
        return voices.get(petId.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public boolean hasVoice(String petId) {
        return resolveVoiceId(petId) != null;
    }
}

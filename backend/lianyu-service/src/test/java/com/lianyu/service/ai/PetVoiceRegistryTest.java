package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PetVoiceRegistryTest {

    @Test
    void loadsRegisteredVoices() {
        PetVoiceRegistry registry = new PetVoiceRegistry(new ObjectMapper());
        registry.load();

        assertTrue(registry.hasVoice("raiden"));
        assertTrue(registry.hasVoice("klee"));
        assertEquals("qwen3-tts-vc-2026-01-22", registry.getModel());
        assertNull(registry.resolveVoiceId("unknown-pet"));
    }
}

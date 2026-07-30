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
        assertTrue(registry.hasVoice("elysia"));
        assertTrue(registry.hasVoice("erii_uesugi"));
        assertTrue(registry.hasVoice("yae_miko"));
        assertTrue(registry.hasVoice("kokomi"));
        assertTrue(registry.hasVoice("shenhe"));
        assertTrue(registry.hasVoice("nahida"));
        assertTrue(registry.hasVoice("hu_tao"));
        assertTrue(registry.hasVoice("furina"));
        assertTrue(registry.hasVoice("noelle"));
        assertTrue(registry.hasVoice("kurumi"));
        assertTrue(registry.hasRealtimeVoice("raiden"));
        assertTrue(registry.hasRealtimeVoice("kurumi"));
        assertTrue(registry.hasRealtimeVoice("yae_miko"));
        assertEquals("qwen3-tts-vc-2026-01-22", registry.getModel());
        assertEquals("qwen3-tts-vc-realtime-2026-01-15", registry.getRealtimeModel());
        assertEquals("qwen-tts-vc-elysia-voice-20260730113705231-3dac", registry.resolveHttpVoiceId("elysia"));
        assertEquals("qwen-tts-vc-eriri-voice-20260720205936884-cd40", registry.resolveHttpVoiceId("erii_uesugi"));
        assertEquals("qwen-tts-vc-raiden-voice-20260616133012759-26cf", registry.resolveHttpVoiceId("raiden"));
        assertEquals("qwen-tts-vc-kurumi-voice-20260730202232102-d557", registry.resolveHttpVoiceId("kurumi"));
        assertEquals("qwen-tts-vc-raiden_rt-voice-20260729051221572-94e4",
                registry.resolveRealtimeVoiceId("raiden"));
        assertEquals("qwen-tts-vc-kurumi_rt-voice-20260730202914798-1cdd",
                registry.resolveRealtimeVoiceId("kurumi"));
        assertNull(registry.resolveRealtimeVoiceId("klee"));
        assertNull(registry.resolveVoiceId("unknown-pet"));
        // HTTP and realtime IDs must stay distinct for the same pet
        assertTrue(!registry.resolveHttpVoiceId("raiden")
                .equals(registry.resolveRealtimeVoiceId("raiden")));
        assertTrue(!registry.resolveHttpVoiceId("kurumi")
                .equals(registry.resolveRealtimeVoiceId("kurumi")));
    }
}

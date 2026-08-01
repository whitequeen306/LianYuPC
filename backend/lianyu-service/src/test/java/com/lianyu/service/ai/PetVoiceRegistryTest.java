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
        assertTrue(registry.hasRealtimeVoice("raiden"));
        assertTrue(registry.hasRealtimeVoice("elysia"));
        assertTrue(registry.hasRealtimeVoice("yae_miko"));
        assertTrue(registry.hasRealtimeVoice("klee"));
        assertTrue(registry.hasRealtimeVoice("ganyu"));
        assertTrue(registry.hasRealtimeVoice("ayaka"));
        assertTrue(registry.hasRealtimeVoice("erii_uesugi"));
        assertEquals("qwen3-tts-vc-2026-01-22", registry.getModel());
        assertEquals("qwen3-tts-vc-realtime-2026-01-15", registry.getRealtimeModel());
        assertEquals("qwen-tts-vc-elysia-voice-20260730113705231-3dac", registry.resolveHttpVoiceId("elysia"));
        assertEquals("qwen-tts-vc-eriri-voice-20260720205936884-cd40", registry.resolveHttpVoiceId("erii_uesugi"));
        assertEquals("qwen-tts-vc-raiden-voice-20260616133012759-26cf", registry.resolveHttpVoiceId("raiden"));
        assertNull(registry.resolveHttpVoiceId("kurumi"));
        assertEquals("qwen-tts-vc-raiden_rt-voice-20260729051221572-94e4",
                registry.resolveRealtimeVoiceId("raiden"));
        assertEquals("qwen-tts-vc-elysia_rt-voice-20260730224949832-f3df",
                registry.resolveRealtimeVoiceId("elysia"));
        assertEquals("qwen-tts-vc-klee_rt-voice-20260801194458612-ede2",
                registry.resolveRealtimeVoiceId("klee"));
        assertEquals("qwen-tts-vc-erii_uesugi_rt-voice-20260801194509981-21fb",
                registry.resolveRealtimeVoiceId("erii_uesugi"));
        assertNull(registry.resolveRealtimeVoiceId("kurumi"));
        assertNull(registry.resolveVoiceId("unknown-pet"));
        // HTTP and realtime IDs must stay distinct for the same pet
        assertTrue(!registry.resolveHttpVoiceId("raiden")
                .equals(registry.resolveRealtimeVoiceId("raiden")));
        assertTrue(!registry.resolveHttpVoiceId("elysia")
                .equals(registry.resolveRealtimeVoiceId("elysia")));
        assertTrue(!registry.resolveHttpVoiceId("klee")
                .equals(registry.resolveRealtimeVoiceId("klee")));
    }
}

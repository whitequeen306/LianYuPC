package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.agent.TtsPort;
import com.lianyu.service.ai.PetVoiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 语种覆盖只影响 request 的 languageType，不改变其它参数。
 *
 * <p>P3 多语言 TTS 用一条覆盖路径：默认中文不变，只有调用方显式传语种才切换。
 */
class VoiceSynthesisServiceTest {

    private TtsPort ttsPort;
    private VoiceSynthesisService service;

    private static TtsPort.TtsChunkSink sink() {
        return new TtsPort.TtsChunkSink() {
            @Override
            public void onChunk(byte[] pcm) {
            }

            @Override
            public void onDone() {
            }

            @Override
            public void onError(String message) {
            }
        };
    }

    @BeforeEach
    void setUp() {
        ttsPort = mock(TtsPort.class);
        PetVoiceRegistry registry = mock(PetVoiceRegistry.class);
        when(registry.resolveHttpVoiceId("raiden")).thenReturn("voice-1");
        when(registry.getModel()).thenReturn("model-http");
        service = new VoiceSynthesisService(ttsPort, registry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        ReflectionTestUtils.setField(service, "languageType", "Chinese");
    }

    private TtsPort.SynthesizeRequest captureRequest() {
        ArgumentCaptor<TtsPort.SynthesizeRequest> captor =
                ArgumentCaptor.forClass(TtsPort.SynthesizeRequest.class);
        verify(ttsPort).streamSynthesize(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void languageOverrideReachesTheRequest() {
        service.streamForPet("raiden", "I'm here.", 1.0, "English", sink());

        assertEquals("English", captureRequest().languageType());
    }

    @Test
    void defaultLanguageIsKeptWhenNoOverride() {
        service.streamForPet("raiden", "嗯，我在。", 1.0, sink());

        assertEquals("Chinese", captureRequest().languageType());
    }
}

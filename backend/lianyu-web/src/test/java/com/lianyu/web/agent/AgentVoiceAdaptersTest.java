package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.agent.TtsPort;
import com.lianyu.service.agent.VoiceEnrollmentPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 语音两条端口的适配层契约。
 *
 * <p>两组完全相反的失败语义，必须各自钉住：
 * <ul>
 *   <li>音色注册失败 → 抛 {@code AI_PROVIDER_ERROR}（用户要看到原因）</li>
 *   <li>TTS 合成失败 → 返回 {@code null}（可降级，不能把可选增强变成主链路故障）</li>
 * </ul>
 */
class AgentVoiceAdaptersTest {

    private final ObjectMapper om = new ObjectMapper();
    private AgentRuntimeClient client;
    private AgentVoiceEnrollmentAdapter enrollmentAdapter;
    private AgentTtsAdapter ttsAdapter;

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        when(client.readTree(anyString())).thenAnswer(inv -> om.readTree((String) inv.getArgument(0)));
        enrollmentAdapter = new AgentVoiceEnrollmentAdapter(client);
        ttsAdapter = new AgentTtsAdapter(client);
    }

    // ── 音色注册 ──

    @Test
    void createVoiceReturnsVoiceId() {
        when(client.postJson(eq("/agent/voice/enroll"), eq(23L), anyMap()))
                .thenReturn("{\"voice\":\"v-http\"}");
        String voice = enrollmentAdapter.createVoice(
                23L, "sk-x", null, "qwen3-tts-vc-2026-01-22", "u23_c52",
                new byte[]{1, 2, 3}, "audio/wav", "zh");
        assertEquals("v-http", voice);
    }

    @Test
    void createVoicePropagatesSanitizedMessage() {
        when(client.postJson(eq("/agent/voice/enroll"), anyLong(), anyMap()))
                .thenReturn("{\"error\":\"enroll_failed\",\"message\":\"key [redacted] 无效\"}");
        BusinessException ex = assertThrows(BusinessException.class, () -> enrollmentAdapter.createVoice(
                23L, "sk-x", null, "m", "p", new byte[]{1}, "audio/wav", "zh"));
        assertEquals(ErrorCode.AI_PROVIDER_ERROR, ex.getErrorCode());
        assertEquals("key [redacted] 无效", ex.getMessage());
    }

    @Test
    void createVoiceFailsWhenAgentUnreachable() {
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> enrollmentAdapter.createVoice(
                23L, "sk-x", null, "m", "p", new byte[]{1}, "audio/wav", "zh"));
        assertEquals(ErrorCode.AI_PROVIDER_ERROR, ex.getErrorCode());
    }

    @Test
    void createVoiceFailsWhenVoiceMissing() {
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn("{}");
        assertThrows(BusinessException.class, () -> enrollmentAdapter.createVoice(
                23L, "sk-x", null, "m", "p", new byte[]{1}, "audio/wav", "zh"));
    }

    @Test
    void deleteVoiceIsSkippedWithoutInputs() {
        enrollmentAdapter.deleteVoiceQuietly(23L, "", null, "v1");
        enrollmentAdapter.deleteVoiceQuietly(23L, "sk-x", null, null);
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void deleteVoiceSwallowsTransportErrors() {
        when(client.postJson(anyString(), anyLong(), anyMap())).thenThrow(new RuntimeException("boom"));
        enrollmentAdapter.deleteVoiceQuietly(23L, "sk-x", null, "v1");
        // 不抛即通过
    }

    // ── TTS 合成 ──

    @Test
    void synthesizeReturnsAudio() {
        when(client.postBytes(eq("/agent/tts/synthesize"), anyLong(), anyMap()))
                .thenReturn(new AgentRuntimeClient.BinaryResponse("audio/wav", new byte[]{82, 73, 70, 70}));
        TtsPort.SynthesizedAudio audio = ttsAdapter.synthesize(
                new TtsPort.SynthesizeRequest("sk-x", null, "m", "v1", "在呢", "Chinese", "pet"));
        assertEquals("audio/wav", audio.mimeType());
        assertEquals(4, audio.bytes().length);
        assertTrue(audio.base64().length() > 0);
    }

    @Test
    void synthesizeReturnsNullWhenAgentUnreachable() {
        when(client.postBytes(anyString(), anyLong(), anyMap())).thenReturn(null);
        assertNull(ttsAdapter.synthesize(
                new TtsPort.SynthesizeRequest("sk-x", null, "m", "v1", "在呢", "Chinese", "pet")));
    }

    @Test
    void synthesizeReturnsNullWhenNoAudioBytes() {
        when(client.postBytes(anyString(), anyLong(), anyMap()))
                .thenReturn(new AgentRuntimeClient.BinaryResponse("audio/wav", new byte[0]));
        assertNull(ttsAdapter.synthesize(
                new TtsPort.SynthesizeRequest("sk-x", null, "m", "v1", "在呢", "Chinese", "pet")));
    }

    @Test
    void synthesizeSkipsEmptyVoiceId() {
        assertNull(ttsAdapter.synthesize(
                new TtsPort.SynthesizeRequest("sk-x", null, "m", "  ", "在呢", "Chinese", "pet")));
        verify(client, never()).postBytes(anyString(), anyLong(), any());
    }

    @Test
    void synthesizeNullRequestSafe() {
        assertNull(ttsAdapter.synthesize(null));
    }

    @Test
    void synthesizedAudioOfToleratesNullBytes() {
        assertNull(TtsPort.SynthesizedAudio.of("audio/wav", null));
        assertNull(TtsPort.SynthesizedAudio.of("audio/wav", new byte[0]));
        assertEquals("audio/wav", TtsPort.SynthesizedAudio.of(null, new byte[]{1}).mimeType());
    }

    /** 端口类型必须真的被适配器实现（防止有人只留接口不接线）。 */
    @Test
    void adaptersImplementPorts() {
        assertTrue(VoiceEnrollmentPort.class.isAssignableFrom(AgentVoiceEnrollmentAdapter.class));
        assertTrue(TtsPort.class.isAssignableFrom(AgentTtsAdapter.class));
    }
}

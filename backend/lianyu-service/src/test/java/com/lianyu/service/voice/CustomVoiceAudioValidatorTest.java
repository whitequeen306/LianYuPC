package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.common.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class CustomVoiceAudioValidatorTest {

    @Test
    void rejectsWrongExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "audio", "x.txt", "text/plain", new byte[20_000]);
        assertThrows(BusinessException.class, () -> CustomVoiceAudioValidator.validate(file));
    }

    @Test
    void acceptsValidWav() {
        byte[] wav = minimalPcmWav(48000, 1, 16, 6.0);
        MockMultipartFile file = new MockMultipartFile(
                "audio", "sample.wav", "audio/wav", wav);
        var validated = CustomVoiceAudioValidator.validate(file);
        assertEquals("wav", validated.extension());
        assertTrue(validated.durationSec() != null && validated.durationSec() >= 5.0);
    }

    @Test
    void allowsLongWavOverOldMaxDuration() {
        byte[] wav = minimalPcmWav(16000, 1, 16, 200.0);
        MockMultipartFile file = new MockMultipartFile(
                "audio", "long.wav", "audio/wav", wav);
        var validated = CustomVoiceAudioValidator.validate(file);
        assertTrue(validated.durationSec() != null && validated.durationSec() > 30.0);
    }

    @Test
    void trimsLongWavToKeepSeconds() {
        byte[] wav = minimalPcmWav(16000, 1, 16, 200.0);
        byte[] trimmed = CustomVoiceAudioTrimmer.trimToWav(wav, "wav");
        org.junit.jupiter.api.Assumptions.assumeTrue(trimmed != null, "ffmpeg not available");
        assertTrue(trimmed.length > 0 && trimmed.length < wav.length);
        Double dur = CustomVoiceAudioValidator.wavDurationSec(trimmed);
        assertTrue(dur != null && dur <= CustomVoiceAudioTrimmer.KEEP_SECONDS + 1.0);
    }

    private static byte[] minimalPcmWav(int sampleRate, int channels, int bits, double seconds) {
        int dataSize = (int) (sampleRate * channels * (bits / 8) * seconds);
        ByteBuffer bb = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt(36 + dataSize);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * channels * bits / 8);
        bb.putShort((short) (channels * bits / 8));
        bb.putShort((short) bits);
        bb.put("data".getBytes());
        bb.putInt(dataSize);
        bb.put(new byte[dataSize]);
        return bb.array();
    }
}

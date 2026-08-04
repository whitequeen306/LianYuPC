package com.lianyu.service.voice;

/**
 * Resolved TTS target for a voice call. Official pets and user-custom voices are separate.
 */
public record VoiceCallTarget(
        Mode mode,
        String petId,
        String httpVoiceId,
        String realtimeVoiceId,
        String apiKey,
        String refAudioUrl,
        String refText,
        /** Local SoVITS endpoint, or DashScope API origin. */
        String endpoint,
        String httpModel,
        String realtimeModel
) {
    public enum Mode {
        OFFICIAL_PET,
        CUSTOM_DASHSCOPE,
        CUSTOM_LOCAL
    }

    public boolean isLocal() {
        return mode == Mode.CUSTOM_LOCAL;
    }

    public boolean isCustomDashScope() {
        return mode == Mode.CUSTOM_DASHSCOPE;
    }
}

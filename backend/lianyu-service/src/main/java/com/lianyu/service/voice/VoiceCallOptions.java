package com.lianyu.service.voice;

/**
 * 语音通话的运行时开关（由 {@code VoiceDuplexHandler} 从配置 + 客户端 caps 协商后传入）。
 *
 * <p>三项都默认关：新增自动能力一律取最保守的一侧（AGENTS §3.4），
 * 部署侧验收后通过 `.env` 逐项打开。
 *
 * @param binaryAudio    下行 PCM 走二进制帧（需客户端 caps 含 {@code binary.audio} 且配置开启）
 * @param clipEnabled    允许下发预录语气词 {@code tts.clip}（sigh/laugh/breath/pickup…）
 * @param openingEnabled 接通后主动说开场白（pickup clip + Python 生成 short cue）
 */
public record VoiceCallOptions(
        boolean binaryAudio,
        boolean clipEnabled,
        boolean openingEnabled) {

    public static VoiceCallOptions defaults() {
        return new VoiceCallOptions(false, false, false);
    }
}

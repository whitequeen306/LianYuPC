package com.lianyu.service.voice;

/**
 * 语音通话的运行时开关（由 {@code VoiceDuplexHandler} 从配置 + 客户端 caps 协商后传入）。
 *
 * <p>三项都默认关：新增自动能力一律取最保守的一侧（AGENTS §3.4），
 * 部署侧验收后通过 `.env` 逐项打开。
 *
 * @param binaryAudio          下行 PCM 走二进制帧（需客户端 caps 含 {@code binary.audio} 且配置开启）
 * @param clipEnabled          允许下发预录语气词 {@code tts.clip}（sigh/laugh/breath/pickup…）
 * @param openingEnabled       接通后主动说开场白（pickup clip + Python 生成 short cue）
 * @param idleNudgeEnabled     冷场主动开口（静音超时后走 Python {@code /agent/voice/cue} 的 idle）
 * @param emotionProsodyEnabled 按角色情绪微调语速与停顿（数值映射在 Java，情绪判定在 Python/DB）
 * @param idleMs               静音多久算冷场
 * @param idleMinGapMs         两次冷场主动之间的最小间隔
 * @param rateMirrorEnabled    语速镜像：按用户本轮说话的语速微调角色语速（±5%）
 */
public record VoiceCallOptions(
        boolean binaryAudio,
        boolean clipEnabled,
        boolean openingEnabled,
        boolean idleNudgeEnabled,
        boolean emotionProsodyEnabled,
        long idleMs,
        long idleMinGapMs,
        boolean rateMirrorEnabled) {

    /** 冷场阈值：12s 是「对方还在，只是没说话」与「以为掉线」的分界。 */
    public static final long DEFAULT_IDLE_MS = 12_000L;

    /** 每通最多两次冷场主动，间隔不小于 45s —— 主动但不缠人。 */
    public static final long DEFAULT_IDLE_MIN_GAP_MS = 45_000L;

    /** P1 及更早的三参构造：新增能力默认关，行为保持不变。 */
    public VoiceCallOptions(boolean binaryAudio, boolean clipEnabled, boolean openingEnabled) {
        this(binaryAudio, clipEnabled, openingEnabled, false, false,
                DEFAULT_IDLE_MS, DEFAULT_IDLE_MIN_GAP_MS, false);
    }

    /** P2 的七参构造：P3 新增的语速镜像默认关。 */
    public VoiceCallOptions(boolean binaryAudio,
                            boolean clipEnabled,
                            boolean openingEnabled,
                            boolean idleNudgeEnabled,
                            boolean emotionProsodyEnabled,
                            long idleMs,
                            long idleMinGapMs) {
        this(binaryAudio, clipEnabled, openingEnabled, idleNudgeEnabled, emotionProsodyEnabled,
                idleMs, idleMinGapMs, false);
    }

    public static VoiceCallOptions defaults() {
        return new VoiceCallOptions(false, false, false);
    }
}

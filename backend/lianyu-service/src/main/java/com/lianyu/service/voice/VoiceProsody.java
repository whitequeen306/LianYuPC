package com.lianyu.service.voice;

/**
 * 情绪 → 声音参数的纯数值映射。
 *
 * <p>边界：**Java 只做「情绪名 → 数值」**，情绪本身由模型/状态服务判定，
 * 文案（该用什么样的语气）住在 Python prompt 里。这样语调是可测的纯函数，
 * 也避免 Java 里再长出一段「她难过的时候应该轻一点」这种话术。
 *
 * <p>本轮只把 {@link #rate()} 与 {@link #pauseScale()} 送进 TTS：
 * DashScope 的 HTTP 合成确认支持语速，音高/音量要先探针再开（默认不生效）。
 *
 * @param rate       语速倍率（1.0 = 模型默认）
 * @param pitch      音高偏移（半音，暂不生效，留给探针后的版本）
 * @param volumeDb   音量偏移（dB，暂不生效）
 * @param pauseScale 句间停顿缩放（1.0 = 原样；难过时拉长，兴奋时收紧）
 */
public record VoiceProsody(double rate, double pitch, double volumeDb, double pauseScale) {

    public static final VoiceProsody NEUTRAL = new VoiceProsody(1.0, 0.0, 0.0, 1.0);

    /** 语速的合理区间：太快的挽留感、太慢的催睡感都不像人。 */
    private static final double RATE_MIN = 0.85;
    private static final double RATE_MAX = 1.12;
    private static final double PAUSE_MIN = 0.75;
    private static final double PAUSE_MAX = 1.45;

    /**
     * 按情绪与强度（0-100）映射成韵律参数。
     *
     * <p>强度用 0.4-1.0 的比例缩放偏移量：50 分是「有点」，100 分才吃满整段幅度，
     * 否则角色一有点情绪就换了个声音，听感很跳。
     */
    public static VoiceProsody resolve(String emotion, double intensity) {
        if (emotion == null || emotion.isBlank()) {
            return NEUTRAL;
        }
        double strength = 0.4 + 0.6 * clamp(intensity / 100.0);
        String e = emotion.trim();
        return switch (e) {
            case "开心" -> scaled(strength, 0.06, 0.0, 0.0, 0.82);
            case "兴奋" -> scaled(strength, 0.08, 0.0, 0.5, 0.78);
            case "难过" -> scaled(strength, -0.08, -0.5, -2.0, 1.30);
            case "疲惫" -> scaled(strength, -0.08, 0.0, -2.0, 1.30);
            case "撒娇" -> scaled(strength, -0.03, 0.5, 0.0, 1.15);
            case "想念" -> scaled(strength, -0.03, 0.0, 0.0, 1.15);
            case "生气" -> scaled(strength, 0.03, 0.0, 0.5, 0.90);
            case "吃醋" -> scaled(strength, 0.02, 0.0, 0.0, 0.92);
            case "担心" -> scaled(strength, -0.02, 0.0, 0.0, 1.10);
            default -> NEUTRAL;
        };
    }

    private static VoiceProsody scaled(double strength, double rateDelta, double pitchDelta,
                                       double volumeDelta, double pauseTarget) {
        double rate = clampRange(1.0 + rateDelta * strength, RATE_MIN, RATE_MAX);
        double pause = clampRange(1.0 + (pauseTarget - 1.0) * strength, PAUSE_MIN, PAUSE_MAX);
        return new VoiceProsody(round(rate), round(pitchDelta * strength),
                round(volumeDelta * strength), round(pause));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clampRange(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

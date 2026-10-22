package com.lianyu.service.voice;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates user-supplied DashScope API base URLs (SSRF-safe allowlist).
 *
 * <p>职责边界：本类只做**写库前的输入校验**（「用户填的地址能不能存」），
 * 以及 Realtime WS 端点（该链路仍在 Java）。音色注册与非实时合成的出站 URL
 * 一律由 Python 侧拼装（`app/dashscope.py`）—— 出站地址的最终形态归发起调用的那一侧，
 * 两边各拼一套必然分叉（`enrollUrl` / `synthUrl` 已随客户端一起删除）。
 */
public final class DashScopeCloudEndpointValidator {

    public static final String DEFAULT_BASE = "https://dashscope.aliyuncs.com";
    /**
     * 现役模型（2026-10-21 实测，见 docs/refactor-2026-10/tts-migration-plan.md）：
     * <ul>
     *   <li>{@code qwen3-tts-vc-*} —— 已下架，合成报 {@code Model not exist}</li>
     *   <li>{@code qwen3-tts-flash} —— 可用，但<b>不支持声音复刻</b>，只有系统音色</li>
     *   <li>{@code qwen-audio-3.1-tts-flash} —— 支持复刻，但生产账号未开通（Engine error 411）</li>
     *   <li>{@code cosyvoice-v3-flash} —— 可用，但同句合成基频离参考音频差 28Hz（音色漂移）</li>
 *   <li>{@code cosyvoice-v3.5-flash} —— 角色音色走它：基频差 3Hz，冷启动更快，热首包 862ms</li>
     * </ul>
     *
     * <p>⚠️ 复刻音色<b>绑定 target_model，不能跨模型</b>：换模型必须重新注册 13 个音色
     * （{@code scripts/re_enroll_pet_voices.py}），旧 voice_id 在新模型上报 Engine error 418。
     */
    public static final String RECOMMENDED_HTTP_MODEL = "cosyvoice-v3.5-flash";
    /**
     * 复刻音色的注册目标模型。
     *
     * <p>注意它<b>不是 TTS 引擎</b>：{@code qwen-audio-*-realtime-*} 是语音对话模型
     * （输入音频、另一套事件），拿它喂 {@code input_text_buffer} 不会出音频。
     * 保留这个常量只为「用户自定义音色注册」这一步仍然合法（该模型接受音色注册），
     * 实时合成链路已不再使用它。
     */
    public static final String RECOMMENDED_REALTIME_MODEL = "qwen-audio-3.1-realtime-plus";

    private static final Pattern SAFE_MODEL = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");
    private static final Pattern ALLOWED_HOST = Pattern.compile(
            "^(dashscope(-intl)?\\.aliyuncs\\.com)$", Pattern.CASE_INSENSITIVE);

    private DashScopeCloudEndpointValidator() {
    }

    public static String normalizeBaseUrl(String raw) {
        String input = raw == null || raw.isBlank() ? DEFAULT_BASE : raw.trim();
        URI uri;
        try {
            uri = URI.create(input);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API 地址格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API 地址仅支持 https");
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOST.matcher(host).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "API 地址仅支持 dashscope.aliyuncs.com / dashscope-intl.aliyuncs.com");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API 地址不能包含用户信息");
        }
        String base = scheme + "://" + host.toLowerCase(Locale.ROOT);
        if (uri.getPort() > 0 && uri.getPort() != 443) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API 地址不支持自定义端口");
        }
        return base;
    }

    public static String normalizeModel(String raw, String recommended, String label) {
        String m = raw == null || raw.isBlank() ? recommended : raw.trim();
        if (!SAFE_MODEL.matcher(m).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + " 模型名无效");
        }
        return m;
    }

}

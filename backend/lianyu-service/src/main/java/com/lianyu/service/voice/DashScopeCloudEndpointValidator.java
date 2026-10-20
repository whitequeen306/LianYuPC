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
    public static final String RECOMMENDED_HTTP_MODEL = "qwen3-tts-vc-2026-01-22";
    public static final String RECOMMENDED_REALTIME_MODEL = "qwen3-tts-vc-realtime-2026-01-15";

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

    public static String realtimeWsUrl(String base) {
        String normalized = normalizeBaseUrl(base);
        String host = URI.create(normalized).getHost();
        return "wss://" + host + "/api-ws/v1/realtime";
    }
}

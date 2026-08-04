package com.lianyu.service.voice;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates local TTS endpoints the client will call. Backend never fetches these URLs.
 */
public final class LocalTtsEndpointValidator {

    private static final Pattern IPV4 = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private LocalTtsEndpointValidator() {
    }

    public static String normalizeAndValidate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写本地语音服务地址");
        }
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地语音服务地址格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地语音服务仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地语音服务地址缺少主机名");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (!isLoopbackOrPrivate(h)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "本地语音服务地址仅允许本机或局域网（127.0.0.1 / localhost / 私有网段）");
        }
        if (trimmed.length() > 512) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地语音服务地址过长");
        }
        // Strip trailing slash for stable storage
        while (trimmed.endsWith("/") && trimmed.length() > 8) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static boolean isLoopbackOrPrivate(String host) {
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || host.endsWith(".local") || host.endsWith(".localhost")) {
            return true;
        }
        var m = IPV4.matcher(host);
        if (!m.matches()) {
            return false;
        }
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        int c = Integer.parseInt(m.group(3));
        int d = Integer.parseInt(m.group(4));
        if (a > 255 || b > 255 || c > 255 || d > 255) {
            return false;
        }
        // 10/8, 172.16/12, 192.168/16, 127/8
        if (a == 10 || a == 127) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        return a == 172 && b >= 16 && b <= 31;
    }
}

package com.lianyu.common.util;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从角色 settings 解析常用字段（city / location）。
 */
public final class CharacterSettingsUtils {

    private static final Charset WINDOWS_1252 = Charset.forName("Windows-1252");

    private CharacterSettingsUtils() {
    }

    public static String resolveCity(Map<String, Object> settings, String defaultCity) {
        if (MapUtil.isEmpty(settings)) {
            return defaultCity;
        }
        String city = MapUtil.getStr(settings, "city");
        if (StrUtil.isNotBlank(city)) {
            return city.trim();
        }
        String location = MapUtil.getStr(settings, "location");
        if (StrUtil.isNotBlank(location)) {
            return location.trim();
        }
        return defaultCity;
    }

    public static String resolveCity(String requestedCity, Map<String, Object> settings, String defaultCity) {
        if (StrUtil.isNotBlank(requestedCity)) {
            return requestedCity.trim();
        }
        return resolveCity(settings, defaultCity);
    }

    /**
     * 修复 UTF-8 字节被误按 Windows-1252 / Latin-1 解码导致的乱码。
     */
    public static String fixUtf8Mojibake(String value) {
        if (StrUtil.isBlank(value) || looksLikeValidCjk(value) || !looksLikeUtf8Mojibake(value)) {
            return value;
        }
        try {
            byte[] bytes = value.getBytes(WINDOWS_1252);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (StrUtil.isNotBlank(decoded) && looksLikeValidCjk(decoded)) {
                return decoded;
            }
        } catch (CharacterCodingException ignored) {
            // keep original
        }
        return value;
    }

    private static boolean looksLikeValidCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> sanitizeSettingsForResponse(Map<String, Object> settings) {
        if (MapUtil.isEmpty(settings)) {
            return settings;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(settings.size());
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str) {
                sanitized.put(entry.getKey(), fixUtf8Mojibake(str));
            } else {
                sanitized.put(entry.getKey(), value);
            }
        }
        return sanitized;
    }

    private static boolean looksLikeUtf8Mojibake(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x00C0 && c <= 0x00FF) {
                return true;
            }
            if (c == 0x0152 || c == 0x0153 || c == 0x0160 || c == 0x0161
                    || c == 0x0178 || c == 0x017D || c == 0x017E
                    || (c >= 0x2013 && c <= 0x201E) || c == 0x2026) {
                return true;
            }
        }
        return false;
    }
}

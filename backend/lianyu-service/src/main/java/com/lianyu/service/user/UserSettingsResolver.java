package com.lianyu.service.user;

import java.util.HashMap;
import java.util.Map;

/**
 * User-level prefs in {@code user.settings_json}.
 */
public final class UserSettingsResolver {

    public static final String KEY_SHOW_CHARACTERS_ON_PROFILE = "showCharactersOnProfile";
    /** Default ON: missing key means the user still accepts community post toasts. */
    public static final String KEY_COMMUNITY_PUSH_ENABLED = "communityPushEnabled";
    /** 识图来源：platform（平台默认 qwen3.7-flash）| followText（跟随文本 Provider 一次调用）| provider（指定识图 Provider） */
    public static final String KEY_VISION_SOURCE_MODE = "visionSourceMode";
    /** mode=provider 时，purpose=vision 的 vault 别名 */
    public static final String KEY_VISION_SOURCE_PROVIDER = "visionSourceProvider";

    public static final String VISION_MODE_PLATFORM = "platform";
    public static final String VISION_MODE_FOLLOW_TEXT = "followText";
    public static final String VISION_MODE_PROVIDER = "provider";

    private UserSettingsResolver() {
    }

    public static boolean showCharactersOnProfile(Map<String, Object> settings) {
        return resolveBoolean(settings, KEY_SHOW_CHARACTERS_ON_PROFILE, false);
    }

    public static boolean communityPushEnabled(Map<String, Object> settings) {
        return resolveBoolean(settings, KEY_COMMUNITY_PUSH_ENABLED, true);
    }

    public static Map<String, Object> withShowCharacters(Map<String, Object> existing, boolean value) {
        Map<String, Object> next = existing == null ? new HashMap<>() : new HashMap<>(existing);
        next.put(KEY_SHOW_CHARACTERS_ON_PROFILE, value);
        return next;
    }

    public static Map<String, Object> withCommunityPushEnabled(Map<String, Object> existing, boolean value) {
        Map<String, Object> next = existing == null ? new HashMap<>() : new HashMap<>(existing);
        next.put(KEY_COMMUNITY_PUSH_ENABLED, value);
        return next;
    }

    /**
     * 识图来源设置。mode 缺省/非法 → platform；mode≠provider 时 provider 视为无效（返回 null）。
     */
    public static VisionSource visionSource(Map<String, Object> settings) {
        String mode = readString(settings, KEY_VISION_SOURCE_MODE);
        if (!VISION_MODE_FOLLOW_TEXT.equals(mode) && !VISION_MODE_PROVIDER.equals(mode)) {
            mode = VISION_MODE_PLATFORM;
        }
        String provider = VISION_MODE_PROVIDER.equals(mode) ? readString(settings, KEY_VISION_SOURCE_PROVIDER) : null;
        return new VisionSource(mode, provider);
    }

    public static Map<String, Object> withVisionSource(Map<String, Object> existing, String mode, String provider) {
        Map<String, Object> next = existing == null ? new HashMap<>() : new HashMap<>(existing);
        String normalized = VISION_MODE_FOLLOW_TEXT.equals(mode) || VISION_MODE_PROVIDER.equals(mode)
                ? mode : VISION_MODE_PLATFORM;
        next.put(KEY_VISION_SOURCE_MODE, normalized);
        if (VISION_MODE_PROVIDER.equals(normalized) && provider != null && !provider.isBlank()) {
            next.put(KEY_VISION_SOURCE_PROVIDER, provider.trim());
        } else {
            next.remove(KEY_VISION_SOURCE_PROVIDER);
        }
        return next;
    }

    public record VisionSource(String mode, String provider) {
    }

    private static String readString(Map<String, Object> settings, String key) {
        if (settings == null || settings.get(key) == null) {
            return null;
        }
        String s = String.valueOf(settings.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean resolveBoolean(Map<String, Object> settings, String key, boolean fallback) {
        if (settings == null || !settings.containsKey(key) || settings.get(key) == null) {
            return fallback;
        }
        Object raw = settings.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) {
            return false;
        }
        return fallback;
    }
}

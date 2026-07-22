package com.lianyu.service.user;

import java.util.HashMap;
import java.util.Map;

/**
 * User-level prefs in {@code user.settings_json}.
 */
public final class UserSettingsResolver {

    public static final String KEY_SHOW_CHARACTERS_ON_PROFILE = "showCharactersOnProfile";

    private UserSettingsResolver() {
    }

    public static boolean showCharactersOnProfile(Map<String, Object> settings) {
        return resolveBoolean(settings, KEY_SHOW_CHARACTERS_ON_PROFILE, false);
    }

    public static Map<String, Object> withShowCharacters(Map<String, Object> existing, boolean value) {
        Map<String, Object> next = existing == null ? new HashMap<>() : new HashMap<>(existing);
        next.put(KEY_SHOW_CHARACTERS_ON_PROFILE, value);
        return next;
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

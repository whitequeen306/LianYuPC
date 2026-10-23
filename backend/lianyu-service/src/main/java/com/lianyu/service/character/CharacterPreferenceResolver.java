package com.lianyu.service.character;

import com.lianyu.dao.entity.Character;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 角色相处偏好（存于 character.settings JSON）：夜间免打扰、心理活动展示、**操控电脑**等。
 * 缺省值对未配置的老角色生效，无需数据迁移。
 *
 * <p>`desktopEnabled` 是「言出法随」的角色级闸门：默认 **关闭**（opt-in）。
 * 即使客户端开启了全局「电脑能力」，没有这个开关的角色也拿不到 `desk_*` 工具，
 * 只能聊天——这样用户不必担心某个角色突然去动自己的电脑。
 */
public final class CharacterPreferenceResolver {

    public static final String KEY_DO_NOT_DISTURB = "doNotDisturbEnabled";
    public static final String KEY_DND_START_MINUTES = "dndStartMinutes";
    public static final String KEY_DND_END_MINUTES = "dndEndMinutes";
    public static final String KEY_SHOW_INNER_THOUGHTS = "showInnerThoughts";
    /** 该角色是否被允许操控用户电脑（言出法随）。默认 false。 */
    public static final String KEY_DESKTOP_ENABLED = "desktopEnabled";

    private static final int DEFAULT_DND_START = 23 * 60;
    private static final int DEFAULT_DND_END = 8 * 60;

    private CharacterPreferenceResolver() {
    }

    public static boolean isDoNotDisturbActive(Character character) {
        return isDoNotDisturbActive(character != null ? character.getSettings() : null);
    }

    public static boolean isDoNotDisturbActive(Map<String, Object> settings) {
        if (!resolveBoolean(settings, KEY_DO_NOT_DISTURB, true)) {
            return false;
        }
        int start = resolveInt(settings, KEY_DND_START_MINUTES, DEFAULT_DND_START);
        int end = resolveInt(settings, KEY_DND_END_MINUTES, DEFAULT_DND_END);
        LocalTime localNow = LocalTime.now(ZoneId.of("Asia/Shanghai"));
        int now = localNow.getHour() * 60 + localNow.getMinute();
        if (start == end) {
            return true;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    public static boolean showInnerThoughts(Character character) {
        return showInnerThoughts(character != null ? character.getSettings() : null);
    }

    public static boolean showInnerThoughts(Map<String, Object> settings) {
        return resolveBoolean(settings, KEY_SHOW_INNER_THOUGHTS, true);
    }

    /**
     * 该角色是否允许操控用户电脑。
     *
     * <p>**默认 false**：老角色与新角色都要显式打开。理由是不对称的收益——
     * 误开一次可能让角色在你没盯着时动你的电脑，误关一次只是少个功能。
     */
    public static boolean desktopEnabled(Character character) {
        return desktopEnabled(character != null ? character.getSettings() : null);
    }

    public static boolean desktopEnabled(Map<String, Object> settings) {
        return resolveBoolean(settings, KEY_DESKTOP_ENABLED, false);
    }

    public static void applyCreationDefaults(Map<String, Object> settings) {
        if (settings == null) {
            return;
        }
        settings.putIfAbsent(KEY_DO_NOT_DISTURB, true);
        settings.putIfAbsent(KEY_DND_START_MINUTES, DEFAULT_DND_START);
        settings.putIfAbsent(KEY_DND_END_MINUTES, DEFAULT_DND_END);
        settings.putIfAbsent(KEY_SHOW_INNER_THOUGHTS, true);
        settings.putIfAbsent(KEY_DESKTOP_ENABLED, false);
    }

    public static boolean resolveBoolean(Map<String, Object> settings, String key, boolean fallback) {
        if (settings == null) {
            return fallback;
        }
        Object raw = settings.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    public static int resolveInt(Map<String, Object> settings, String key, int fallback) {
        if (settings == null) {
            return fallback;
        }
        Object raw = settings.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}

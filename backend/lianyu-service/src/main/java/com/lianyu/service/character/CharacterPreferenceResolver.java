package com.lianyu.service.character;

import com.lianyu.dao.entity.Character;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 角色相处偏好（存于 character.settings JSON）：夜间免打扰、心理活动展示。
 * 缺省值对未配置的老角色生效，无需数据迁移。
 *
 * <p>2026-10-25：`desktopEnabled`（角色级「允许 TA 操控我的电脑」闸门）随桌面能力层一起下线。
 * 老角色 settings 里残留的这个键**不做数据迁移**（Flyway V55 及表结构都不动），
 * 但 Java 侧不再读取、不再写入、也不再对外暴露 —— 键留着不影响任何行为。
 */
public final class CharacterPreferenceResolver {

    public static final String KEY_DO_NOT_DISTURB = "doNotDisturbEnabled";
    public static final String KEY_DND_START_MINUTES = "dndStartMinutes";
    public static final String KEY_DND_END_MINUTES = "dndEndMinutes";
    public static final String KEY_SHOW_INNER_THOUGHTS = "showInnerThoughts";
    /** 表情包频率：never（从不发）/ normal（正常）/ frequent（非常爱发）。 */
    public static final String KEY_STICKER_FREQUENCY = "stickerFrequency";

    public static final String STICKER_FREQUENCY_NEVER = "never";
    public static final String STICKER_FREQUENCY_NORMAL = "normal";
    public static final String STICKER_FREQUENCY_FREQUENT = "frequent";

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

    /** 表情包频率，未知值一律回退 normal —— 配置写错不该让角色突然变成哑巴。 */
    public static String stickerFrequency(Character character) {
        return stickerFrequency(character != null ? character.getSettings() : null);
    }

    public static String stickerFrequency(Map<String, Object> settings) {
        if (settings == null) {
            return STICKER_FREQUENCY_NORMAL;
        }
        Object raw = settings.get(KEY_STICKER_FREQUENCY);
        if (raw == null) {
            return STICKER_FREQUENCY_NORMAL;
        }
        String value = String.valueOf(raw).trim().toLowerCase();
        return switch (value) {
            case STICKER_FREQUENCY_NEVER, "off", "none", "0" -> STICKER_FREQUENCY_NEVER;
            case STICKER_FREQUENCY_FREQUENT, "often", "high", "2" -> STICKER_FREQUENCY_FREQUENT;
            default -> STICKER_FREQUENCY_NORMAL;
        };
    }

    public static void applyCreationDefaults(Map<String, Object> settings) {
        if (settings == null) {
            return;
        }
        settings.putIfAbsent(KEY_DO_NOT_DISTURB, true);
        settings.putIfAbsent(KEY_DND_START_MINUTES, DEFAULT_DND_START);
        settings.putIfAbsent(KEY_DND_END_MINUTES, DEFAULT_DND_END);
        settings.putIfAbsent(KEY_SHOW_INNER_THOUGHTS, true);
        settings.putIfAbsent(KEY_STICKER_FREQUENCY, STICKER_FREQUENCY_NORMAL);
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

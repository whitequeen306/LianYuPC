package com.lianyu.service.character;

import com.lianyu.dao.entity.Character;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据角色说话风格、人设关键词与 settings.chatBehavior 解析聊天行为。
 * 全局 application.yml 仅作兜底，不同角色应有不同主动频率与连发条数。
 */
@Component
public class CharacterChatBehaviorResolver {

    @Value("${lianyu.chat.max-replies-per-turn:3}")
    private int defaultMaxReplies;

    @Value("${lianyu.chat.proactive.min-idle-minutes:60}")
    private int defaultMinIdle;

    @Value("${lianyu.chat.proactive.trigger-probability:0.95}")
    private double defaultTriggerProbability;

    @Value("${lianyu.chat.proactive.cooldown-min-minutes:55}")
    private int defaultCooldownMin;

    @Value("${lianyu.chat.proactive.cooldown-max-minutes:65}")
    private int defaultCooldownMax;

    public CharacterChatBehavior resolve(Character character) {
        if (character == null) {
            return defaults(null);
        }

        Map<String, Object> settings = character.getSettings();
        String speakingStyle = stringSetting(settings, "speakingStyle");
        String corpus = buildCorpus(character, settings, speakingStyle);

        StyleProfile profile = profileForStyle(speakingStyle);
        profile = adjustProfileByKeywords(profile, corpus);

        boolean proactiveEnabled = profile.proactiveEnabled;
        int maxReplies = profile.maxReplies;
        int minIdle = profile.minIdleMinutes;
        double triggerProb = profile.triggerProbability;
        int cooldownMin = profile.cooldownMinMinutes;
        int cooldownMax = profile.cooldownMaxMinutes;

        Map<String, Object> behaviorMap = nestedChatBehavior(settings);
        if (behaviorMap != null) {
            proactiveEnabled = boolOverride(behaviorMap, "proactiveEnabled", proactiveEnabled);
            maxReplies = intOverride(behaviorMap, "maxRepliesPerTurn", maxReplies);
            minIdle = intOverride(behaviorMap, "minIdleMinutes", minIdle);
            triggerProb = doubleOverride(behaviorMap, "triggerProbability", triggerProb);
            cooldownMin = intOverride(behaviorMap, "cooldownMinMinutes", cooldownMin);
            cooldownMax = intOverride(behaviorMap, "cooldownMaxMinutes", cooldownMax);
        }

        // 扁平字段（高级用户可在额外 JSON 里写）
        if (settings != null) {
            proactiveEnabled = boolOverride(settings, "proactiveEnabled", proactiveEnabled);
            maxReplies = intOverride(settings, "maxRepliesPerTurn", maxReplies);
            minIdle = intOverride(settings, "proactiveMinIdleMinutes", minIdle);
            triggerProb = doubleOverride(settings, "proactiveTriggerProbability", triggerProb);
            cooldownMin = intOverride(settings, "proactiveCooldownMinMinutes", cooldownMin);
            cooldownMax = intOverride(settings, "proactiveCooldownMaxMinutes", cooldownMax);
        }

        return new CharacterChatBehavior(
                clamp(maxReplies, 1, 5),
                proactiveEnabled,
                clamp(minIdle, 3, 120),
                clamp(triggerProb, 0.0, 1.0),
                clamp(cooldownMin, 5, 180),
                clamp(Math.max(cooldownMin, cooldownMax), 5, 240),
                speakingStyle
        );
    }

    private CharacterChatBehavior defaults(String speakingStyle) {
        return new CharacterChatBehavior(
                defaultMaxReplies,
                true,
                defaultMinIdle,
                defaultTriggerProbability,
                defaultCooldownMin,
                defaultCooldownMax,
                speakingStyle
        );
    }

    private String buildCorpus(Character character, Map<String, Object> settings, String speakingStyle) {
        StringBuilder sb = new StringBuilder();
        if (speakingStyle != null) sb.append(speakingStyle).append(' ');
        if (character.getPromptTemplate() != null) sb.append(character.getPromptTemplate()).append(' ');
        if (settings != null) {
            Object p = settings.get("personality");
            if (p != null) sb.append(p).append(' ');
            Object b = settings.get("backstory");
            if (b != null) sb.append(b);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private StyleProfile profileForStyle(String speakingStyle) {
        if (speakingStyle == null || speakingStyle.isBlank()) {
            return StyleProfile.baseline();
        }
        return switch (speakingStyle.trim()) {
            case "活泼", "元气" -> new StyleProfile(true, 3, 45, 0.95, 50, 60);
            case "温柔" -> new StyleProfile(true, 2, 55, 0.92, 55, 65);
            case "傲娇", "毒舌" -> new StyleProfile(true, 2, 65, 0.88, 60, 75);
            case "冷静", "成熟" -> new StyleProfile(true, 1, 75, 0.82, 70, 90);
            case "慵懒" -> new StyleProfile(true, 1, 70, 0.85, 65, 80);
            default -> StyleProfile.baseline();
        };
    }

    private StyleProfile adjustProfileByKeywords(StyleProfile base, String corpus) {
        if (corpus == null || corpus.isBlank()) {
            return base;
        }
        StyleProfile p = base;
        if (containsAny(corpus, "粘人", "话多", "热情", "开朗", "健谈", "爱聊天", "主动")) {
            p = p.moreSocial();
        }
        if (containsAny(corpus, "冷淡", "高冷", "内向", "沉默", "寡言", "疏离", "克制", "少言")) {
            p = p.lessSocial();
        }
        return p;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedChatBehavior(Map<String, Object> settings) {
        if (settings == null) {
            return null;
        }
        Object raw = settings.get("chatBehavior");
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private String stringSetting(Map<String, Object> settings, String key) {
        if (settings == null) {
            return null;
        }
        Object v = settings.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private int intOverride(Map<String, ?> map, String key, int fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleOverride(Map<String, ?> map, String key, double fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean boolOverride(Map<String, ?> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    private int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }

    private double clamp(double v, double min, double max) {
        return Math.min(max, Math.max(min, v));
    }

    private static final class StyleProfile {
        final boolean proactiveEnabled;
        final int maxReplies;
        final int minIdleMinutes;
        final double triggerProbability;
        final int cooldownMinMinutes;
        final int cooldownMaxMinutes;

        StyleProfile(boolean proactiveEnabled, int maxReplies, int minIdleMinutes,
                     double triggerProbability, int cooldownMinMinutes, int cooldownMaxMinutes) {
            this.proactiveEnabled = proactiveEnabled;
            this.maxReplies = maxReplies;
            this.minIdleMinutes = minIdleMinutes;
            this.triggerProbability = triggerProbability;
            this.cooldownMinMinutes = cooldownMinMinutes;
            this.cooldownMaxMinutes = cooldownMaxMinutes;
        }

        static StyleProfile baseline() {
            return new StyleProfile(true, 2, 60, 0.95, 55, 65);
        }

        StyleProfile moreSocial() {
            return new StyleProfile(
                    true,
                    Math.min(5, maxReplies + 1),
                    Math.max(40, minIdleMinutes - 10),
                    Math.min(0.98, triggerProbability + 0.03),
                    Math.max(45, cooldownMinMinutes - 5),
                    Math.max(cooldownMinMinutes + 5, cooldownMaxMinutes - 5)
            );
        }

        StyleProfile lessSocial() {
            return new StyleProfile(
                    proactiveEnabled,
                    Math.max(1, maxReplies - 1),
                    minIdleMinutes + 15,
                    Math.max(0.75, triggerProbability - 0.08),
                    cooldownMinMinutes + 10,
                    cooldownMaxMinutes + 15
            );
        }
    }
}

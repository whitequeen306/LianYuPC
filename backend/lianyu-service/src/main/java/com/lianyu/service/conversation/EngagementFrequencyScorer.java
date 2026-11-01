package com.lianyu.service.conversation;

import com.lianyu.dao.entity.CharacterState;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.relationship.RelationshipPhase;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据角色性格参数、心情/内心状态、关系阶段与近期单聊活跃度，计算主动消息 / 朋友圈的发送权重。
 */
@Component
public class EngagementFrequencyScorer {

    @Value("${lianyu.chat.proactive.activity-window-days:7}")
    private int proactiveActivityWindowDays;

    @Value("${lianyu.moments.activity-window-days:14}")
    private int momentsActivityWindowDays;

    /**
     * 单聊主动消息：有效触发概率（0~1）。聊得多、最近聊过的角色更高。
     */
    public double proactiveTriggerProbability(CharacterChatBehavior behavior,
                                             int userMessagesInWindow,
                                             LocalDateTime lastUserMessageAt,
                                             LocalDateTime now,
                                             CharacterState state,
                                             RelationshipPhase phase) {
        if (behavior == null || !behavior.proactiveEnabled()) {
            return 0.0;
        }
        double base = behavior.triggerProbability();
        if (lastUserMessageAt != null) {
            long idleHours = ChronoUnit.HOURS.between(lastUserMessageAt, now);
            if (idleHours >= Math.max(1, behavior.minIdleMinutes() / 60)) {
                base = clamp(base, 0.85, 0.98);
            }
        }
        double activity = activityBoost(userMessagesInWindow, 0.42, 0.16, 1.75);
        double recency = recencyBoost(lastUserMessageAt, now, 72, 0.62);
        double personality = personalityMultiplier(state, phase);
        return clamp(base * activity * recency * personality, 0.08, 0.98);
    }

    /**
     * 心情 + 关系阶段对主动开口意愿的加权（不同角色不应千篇一律）。
     */
    public double personalityMultiplier(CharacterState state, RelationshipPhase phase) {
        double emotionMul = emotionMultiplier(state != null ? state.getCurrentEmotion() : null);
        double intensityMul = emotionIntensityMultiplier(state != null ? state.getEmotionIntensity() : null);
        double innerMul = innerStateMultiplier(state);
        double relationMul = relationshipMultiplier(phase);
        return clamp(emotionMul * intensityMul * innerMul * relationMul, 0.35, 1.55);
    }

    /**
     * 心情越「想找人说话」，同等空闲时间下可略缩短等待（分钟）。
     */
    public int adjustMinIdleMinutes(int baseMinutes, CharacterState state, RelationshipPhase phase) {
        double mul = personalityMultiplier(state, phase);
        if (mul >= 1.2) {
            return Math.max(15, (int) Math.round(baseMinutes * 0.82));
        }
        if (mul <= 0.65) {
            return Math.min(180, (int) Math.round(baseMinutes * 1.25));
        }
        return baseMinutes;
    }

    /**
     * 朋友圈：在全局概率上乘以活跃度系数（0~1），冷会话几乎不发。
     */
    public double momentsProbabilityMultiplier(int userMessagesInWindow,
                                               LocalDateTime lastUserMessageAt,
                                               LocalDateTime now) {
        if (lastUserMessageAt == null) {
            return 0.08;
        }
        long daysSince = ChronoUnit.DAYS.between(lastUserMessageAt, now);
        if (daysSince > 30) {
            return 0.05;
        }
        double activity = activityBoost(userMessagesInWindow, 0.22, 0.07, 1.0);
        double recency = recencyBoost(lastUserMessageAt, now, 168, 0.35);
        return clamp(activity * recency, 0.05, 1.0);
    }

    public int proactiveActivityWindowDays() {
        return Math.max(1, proactiveActivityWindowDays);
    }

    public int momentsActivityWindowDays() {
        return Math.max(1, momentsActivityWindowDays);
    }

    private static double activityBoost(int messageCount, double floor, double perMessage, double cap) {
        return Math.min(cap, floor + Math.max(0, messageCount) * perMessage);
    }

    /**
     * 最近 N 小时内聊过 → 加成；过久未聊 → 衰减。
     */
    private static double recencyBoost(LocalDateTime lastAt, LocalDateTime now,
                                      long sweetSpotHours, double maxExtra) {
        if (lastAt == null) {
            return 0.5;
        }
        long hours = ChronoUnit.HOURS.between(lastAt, now);
        if (hours <= sweetSpotHours) {
            return 1.0 + (sweetSpotHours - hours) / (double) sweetSpotHours * maxExtra;
        }
        long over = hours - sweetSpotHours;
        return Math.max(0.45, 1.0 - over / 336.0);
    }

    private static double emotionMultiplier(String emotion) {
        if (emotion == null || emotion.isBlank()) {
            return 1.0;
        }
        return switch (emotion.trim()) {
            case "想念" -> 1.38;
            case "撒娇" -> 1.30;
            case "吃醋" -> 1.22;
            case "担心" -> 1.18;
            case "开心", "兴奋" -> 1.15;
            case "平静" -> 1.0;
            case "疲惫" -> 0.62;
            case "难过" -> 0.68;
            case "生气" -> 0.42;
            default -> 1.0;
        };
    }

    private static double emotionIntensityMultiplier(Integer intensity) {
        if (intensity == null) {
            return 1.0;
        }
        int safe = Math.min(100, Math.max(0, intensity));
        return 0.82 + (safe / 100.0) * 0.36;
    }

    private static double innerStateMultiplier(CharacterState state) {
        if (state == null) {
            return 1.0;
        }
        String status = state.getStatusText();
        if (status == null || status.isBlank()) {
            return 1.0;
        }
        String text = status.trim();
        if (containsAny(text, "想见", "好想你", "想你了", "好想见", "等你", "别走", "别不理", "怎么还不")) {
            return 1.12;
        }
        if (containsAny(text, "不想理", "别烦", "好累", "好烦", "生气", "难过", "不开心")) {
            return 0.78;
        }
        if (containsAny(text, "有好多话", "想跟你说", "心情很好", "好开心")) {
            return 1.08;
        }
        return 1.0;
    }

    private static double relationshipMultiplier(RelationshipPhase phase) {
        if (phase == null) {
            return 1.0;
        }
        return switch (phase) {
            case STABLE_INTIMATE -> 1.40;
            case DEPENDENT -> 1.25;
            case FAMILIAR -> 1.08;
            case REPAIRING -> 0.90;
            case TESTING -> 0.85;
            // 受伤后彻底消失会被理解成被抛弃：降频但不沉默，留一条修复的通道
            case INJURED -> 0.25;
        };
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double v, double min, double max) {
        return Math.min(max, Math.max(min, v));
    }
}

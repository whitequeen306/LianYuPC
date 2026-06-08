package com.lianyu.service.conversation;

import com.lianyu.service.character.CharacterChatBehavior;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据角色性格参数 + 近期单聊活跃度，计算主动消息 / 朋友圈的发送权重。
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
                                             LocalDateTime now) {
        if (behavior == null || !behavior.proactiveEnabled()) {
            return 0.0;
        }
        double base = behavior.triggerProbability();
        if (lastUserMessageAt != null) {
            long idleHours = ChronoUnit.HOURS.between(lastUserMessageAt, now);
            if (idleHours >= Math.max(1, behavior.minIdleMinutes() / 60)) {
                return clamp(base, 0.85, 0.98);
            }
        }
        double activity = activityBoost(userMessagesInWindow, 0.42, 0.16, 1.75);
        double recency = recencyBoost(lastUserMessageAt, now, 72, 0.62);
        return clamp(base * activity * recency, 0.10, 0.98);
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

    private static double clamp(double v, double min, double max) {
        return Math.min(max, Math.max(min, v));
    }
}

package com.lianyu.service.relationship;

import java.time.LocalDateTime;

/**
 * 「陪了多久」这一组事实。分数说的是关系质量，这里说的是关系的时间体量 ——
 * 用户对「第 30 天」的感知远强于「亲密 62 分」。
 */
public record RelationshipPresence(
        int daysKnown,
        int streakDays,
        long awayHours,
        LocalDateTime lastInteractionAt,
        RelationshipPhase nextPhase,
        int progressToNext) {

    public static RelationshipPresence empty() {
        return new RelationshipPresence(0, 0, 0L, null, RelationshipPhase.FAMILIAR, 0);
    }
}

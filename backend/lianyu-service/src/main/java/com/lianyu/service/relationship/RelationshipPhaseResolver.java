package com.lianyu.service.relationship;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 阶段推导的唯一事实来源（写入路径与时间衰减任务共用）。
 *
 * 旧实现把 INJURED / REPAIRING 挂在「本批次事件」上，导致这两种状态只活一个回合；
 * 现在由 last_injury_at / last_repair_at 驱动：受伤会粘住，修复有自己的窗口期。
 */
public final class RelationshipPhaseResolver {

    public static final int INJURY_SECURITY_CEILING = 30;
    public static final Duration INJURY_HOLD = Duration.ofHours(72);
    public static final Duration REPAIR_HOLD = Duration.ofHours(24);

    public static final int FAMILIAR_TRUST = 45;
    public static final int DEPENDENT_INTIMACY = 55;
    public static final int STABLE_INTIMACY = 70;
    public static final int STABLE_TRUST = 65;

    private RelationshipPhaseResolver() {
    }

    public static RelationshipPhase derive(RelationshipSnapshot snapshot,
                                           LocalDateTime lastInjuryAt,
                                           LocalDateTime lastRepairAt,
                                           LocalDateTime now) {
        LocalDateTime safeNow = now == null ? LocalDateTime.now() : now;
        // 「修复」必须先有「受伤」：没有受过伤的关系，一句抱歉不该把状态切到修复中。
        // 旧条件在 lastInjuryAt == null 时也返回 true，导致线上 16 个从未受伤的关系
        // 因为用户说了句「抱歉」被展示成「修复中」。
        boolean repairedAfterInjury = lastRepairAt != null
                && lastInjuryAt != null
                && !lastRepairAt.isBefore(lastInjuryAt);
        if (repairedAfterInjury && lastRepairAt.isAfter(safeNow.minus(REPAIR_HOLD))) {
            return RelationshipPhase.REPAIRING;
        }
        boolean injuryOpen = lastInjuryAt != null
                && (lastRepairAt == null || lastRepairAt.isBefore(lastInjuryAt))
                && lastInjuryAt.isAfter(safeNow.minus(INJURY_HOLD));
        if (injuryOpen && snapshot.securityScore() <= INJURY_SECURITY_CEILING) {
            return RelationshipPhase.INJURED;
        }
        return deriveFromScores(snapshot);
    }

    public static RelationshipPhase deriveFromScores(RelationshipSnapshot snapshot) {
        if (snapshot.intimacyScore() >= STABLE_INTIMACY && snapshot.trustScore() >= STABLE_TRUST) {
            return RelationshipPhase.STABLE_INTIMATE;
        }
        if (snapshot.intimacyScore() >= DEPENDENT_INTIMACY) {
            return RelationshipPhase.DEPENDENT;
        }
        if (snapshot.trustScore() >= FAMILIAR_TRUST) {
            return RelationshipPhase.FAMILIAR;
        }
        return RelationshipPhase.TESTING;
    }

    /**
     * 受伤 / 修复是支线状态，不在成长阶梯上：返回 null 让前端改显示「怎么修复」，
     * 而不是给一条永远走不满的进度条。
     */
    public static RelationshipPhase nextPhase(RelationshipPhase phase) {
        if (phase == null) {
            return RelationshipPhase.FAMILIAR;
        }
        return switch (phase) {
            case TESTING -> RelationshipPhase.FAMILIAR;
            case FAMILIAR -> RelationshipPhase.DEPENDENT;
            case DEPENDENT -> RelationshipPhase.STABLE_INTIMATE;
            default -> null;
        };
    }

    public static int progressToNext(RelationshipSnapshot snapshot, RelationshipPhase phase) {
        if (snapshot == null || phase == null) {
            return 0;
        }
        return switch (phase) {
            case TESTING -> percent(snapshot.trustScore(), FAMILIAR_TRUST);
            case FAMILIAR -> percent(snapshot.intimacyScore(), DEPENDENT_INTIMACY);
            case DEPENDENT -> Math.min(
                    percent(snapshot.intimacyScore(), STABLE_INTIMACY),
                    percent(snapshot.trustScore(), STABLE_TRUST));
            case STABLE_INTIMATE -> 100;
            default -> 0;
        };
    }

    private static int percent(int value, int target) {
        if (target <= 0) {
            return 100;
        }
        return Math.max(0, Math.min(100, (int) Math.round(value * 100.0 / target)));
    }
}

package com.lianyu.service.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.dao.entity.RelationshipState;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 关系重构的探针：每一条在旧实现上都会失败（旧实现里关系几乎不变、驱动不可见）。
 */
class RelationshipReworkTest {

    private static RelationshipSnapshot initial() {
        return RelationshipSnapshot.builder()
                .trustScore(40)
                .intimacyScore(20)
                .securityScore(40)
                .anticipationScore(25)
                .phase(RelationshipPhase.TESTING)
                .build();
    }

    @Test
    void naturalVulnerableWordingIsDetectedWithoutExactScriptedSentence() {
        // 旧实现只认「其实我有点难受 / 我今天很崩溃 / 我有点害怕」三句整句
        List<RelationshipEventInput> events = RelationshipHeuristics.fromUserTurn(
                1L, 2L, 3L, 4L, "今天被领导骂了，我压力好大，有点撑不住", null);

        RelationshipEventInput vulnerable = events.stream()
                .filter(e -> e.eventType() == RelationshipEventType.USER_VULNERABLE_SHARE)
                .findFirst()
                .orElseThrow();
        assertTrue(vulnerable.weight() >= 2);

        RelationshipSnapshot after = RelationshipStateService.applyEvent(initial(), vulnerable);
        assertTrue(after.trustScore() > 40);
        assertTrue(after.intimacyScore() > 20);
        // summary 是用户原话，不再是 Java 写的分类标签
        assertTrue(vulnerable.summary().contains("被领导骂了"));
    }

    @Test
    void warmReplyNowHasAProducerAndScoresIntimacy() {
        List<RelationshipEventInput> events = RelationshipHeuristics.fromUserTurn(
                1L, 2L, 3L, 4L, "晚安，今天也很想你", null);

        RelationshipEventInput warm = events.stream()
                .filter(e -> e.eventType() == RelationshipEventType.USER_WARM_RESPONSE)
                .findFirst()
                .orElseThrow();
        assertTrue(RelationshipStateService.applyEvent(initial(), warm).intimacyScore() > 20);
    }

    @Test
    void nicknameAndRitualEventsFinallyHaveProducers() {
        List<RelationshipEventInput> nickname = RelationshipHeuristics.fromUserTurn(
                1L, 2L, 3L, 4L, "以后你可以叫我阿昼，这是只给你叫的。", null);
        assertTrue(nickname.stream().anyMatch(
                e -> e.eventType() == RelationshipEventType.SPECIAL_NICKNAME_CREATED));

        List<RelationshipEventInput> ritual = RelationshipHeuristics.fromUserTurn(
                1L, 2L, 3L, 4L, "以后每天睡前都跟我说晚安吧", null);
        assertTrue(ritual.stream().anyMatch(
                e -> e.eventType() == RelationshipEventType.RITUAL_ESTABLISHED));
    }

    @Test
    void dailyPresenceAloneGrowsRelationshipWithinTwoWeeks() {
        RelationshipSnapshot snapshot = initial();
        for (int day = 0; day < 12; day++) {
            snapshot = RelationshipStateService.applyEvent(
                    snapshot, RelationshipEventInput.simple(RelationshipEventType.DAILY_PRESENCE, 1));
        }
        assertEquals(56, snapshot.intimacyScore());
        assertEquals(52, snapshot.trustScore());
        assertEquals(RelationshipPhase.DEPENDENT, RelationshipPhaseResolver.deriveFromScores(snapshot));
    }

    @Test
    void comfortingAssistantReplyNowCountsInsteadOfBeingDropped() {
        RelationshipSnapshot after = RelationshipStateService.applyEvent(
                initial(), RelationshipEventInput.simple(RelationshipEventType.ASSISTANT_VULNERABLE_REPLY, 1));
        assertEquals(43, after.securityScore());
        assertEquals(42, after.trustScore());
    }

    @Test
    void injuredStateSurvivesBeyondASingleTurnAndRepairOpensAWindow() {
        LocalDateTime now = LocalDateTime.now();
        RelationshipSnapshot hurt = initial().toBuilder().securityScore(20).build();

        assertEquals(RelationshipPhase.INJURED, RelationshipPhaseResolver.derive(
                hurt, now.minusHours(2), null, now));
        assertEquals(RelationshipPhase.REPAIRING, RelationshipPhaseResolver.derive(
                hurt, now.minusHours(2), now.minusHours(1), now));
        // 伤已经过了保持期且安心回升，就不该一直卡在受伤
        RelationshipSnapshot recovered = hurt.toBuilder().securityScore(40).trustScore(60).build();
        assertEquals(RelationshipPhase.FAMILIAR, RelationshipPhaseResolver.derive(
                recovered, now.minusHours(100), null, now));
    }

    @Test
    void timeAwayBuildsAnticipationAndSlowlyErodesIntimacy() {
        LocalDateTime now = LocalDateTime.now();
        RelationshipState state = new RelationshipState();
        state.setTrustScore(60);
        state.setIntimacyScore(50);
        state.setSecurityScore(30);
        state.setAnticipationScore(25);
        state.setPhase(RelationshipPhase.FAMILIAR.name());
        state.setLastInteractionAt(now.minusDays(5));
        state.setCreatedAt(now.minusDays(10));

        assertTrue(RelationshipStateService.applyDecay(state, now));
        assertEquals(40, state.getAnticipationScore());
        assertEquals(45, state.getIntimacyScore());
        assertEquals(35, state.getSecurityScore());

        // 同一天重复结算不得二次扣减
        assertFalse(RelationshipStateService.applyDecay(state, now));
        assertEquals(45, state.getIntimacyScore());
    }

    @Test
    void apologyWithoutPriorInjuryDoesNotEnterRepairing() {
        // 线上真实事故：16 个从未受伤的关系因为用户说了句「抱歉」被展示成「修复中」
        LocalDateTime now = LocalDateTime.now();
        RelationshipSnapshot fine = initial().toBuilder().trustScore(52).build();

        assertEquals(RelationshipPhase.FAMILIAR, RelationshipPhaseResolver.derive(
                fine, null, now.minusMinutes(5), now));
    }

    @Test
    void decayNeverAppliesMoreThanAMonthAtOnce() {
        // 老账号第一次跑衰减时不该被一口气扣掉几个月（V56 回填的是 updated_at）
        LocalDateTime now = LocalDateTime.now();
        RelationshipState state = new RelationshipState();
        state.setTrustScore(60);
        state.setIntimacyScore(50);
        state.setSecurityScore(40);
        state.setAnticipationScore(25);
        state.setPhase(RelationshipPhase.FAMILIAR.name());
        state.setLastInteractionAt(now.minusDays(200));
        state.setCreatedAt(now.minusDays(400));

        assertTrue(RelationshipStateService.applyDecay(state, now));
        // 上限 30 天：亲密最多 -30 → 20（地板），想念最多 +90 → 100
        assertEquals(20, state.getIntimacyScore());
        assertEquals(100, state.getAnticipationScore());
    }

    @Test
    void progressToNextTellsUserWhatIsLeftInsteadOfShowingABareNumber() {
        assertEquals(89, RelationshipPhaseResolver.progressToNext(initial(), RelationshipPhase.TESTING));
        assertEquals(RelationshipPhase.FAMILIAR, RelationshipPhaseResolver.nextPhase(RelationshipPhase.TESTING));
        assertEquals(100, RelationshipPhaseResolver.progressToNext(initial(), RelationshipPhase.STABLE_INTIMATE));
        assertEquals(null, RelationshipPhaseResolver.nextPhase(RelationshipPhase.INJURED));
    }
}

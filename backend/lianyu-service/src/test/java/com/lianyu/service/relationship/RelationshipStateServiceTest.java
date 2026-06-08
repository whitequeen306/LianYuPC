package com.lianyu.service.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RelationshipStateServiceTest {

    @Test
    void derivePhase_marksInjuredWhenSecurityIsLowAndRecentInjuryExists() {
        RelationshipSnapshot snapshot = RelationshipSnapshot.builder()
                .trustScore(42)
                .intimacyScore(48)
                .securityScore(18)
                .anticipationScore(33)
                .phase(RelationshipPhase.FAMILIAR)
                .build();

        assertEquals(RelationshipPhase.INJURED,
                RelationshipStateService.derivePhase(snapshot, true, false));
    }

    @Test
    void recordEvent_increasesTrustAfterRepairSuccess() {
        RelationshipSnapshot before = RelationshipSnapshot.builder()
                .trustScore(40)
                .intimacyScore(25)
                .securityScore(20)
                .anticipationScore(20)
                .phase(RelationshipPhase.INJURED)
                .build();

        RelationshipSnapshot after = RelationshipStateService.applyEvent(
                before,
                RelationshipEventInput.simple(RelationshipEventType.REPAIR_SUCCESS, 2));

        assertEquals(52, after.trustScore());
        assertEquals(RelationshipPhase.REPAIRING, after.phase());
    }
}

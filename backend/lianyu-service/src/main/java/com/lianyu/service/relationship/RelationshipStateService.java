package com.lianyu.service.relationship;

public final class RelationshipStateService {

    private RelationshipStateService() {
    }

    public static RelationshipSnapshot applyEvent(RelationshipSnapshot before, RelationshipEventInput input) {
        int trust = before.trustScore();
        int intimacy = before.intimacyScore();
        int security = before.securityScore();
        int anticipation = before.anticipationScore();
        switch (input.eventType()) {
            case USER_VULNERABLE_SHARE -> {
                trust += 6 * input.weight();
                intimacy += 4 * input.weight();
            }
            case USER_DISMISSIVE_RESPONSE, USER_BROKE_PROMISE, MISUNDERSTANDING_TRIGGERED -> {
                security -= 8 * input.weight();
                anticipation -= 5 * input.weight();
            }
            case REPAIR_ATTEMPT -> trust += 4 * input.weight();
            case REPAIR_SUCCESS -> {
                trust += 6 * input.weight();
                security += 8 * input.weight();
                intimacy += 3 * input.weight();
            }
            case SPECIAL_NICKNAME_CREATED, RITUAL_ESTABLISHED -> {
                intimacy += 8 * input.weight();
                anticipation += 4 * input.weight();
            }
            default -> {
            }
        }
        RelationshipSnapshot next = before.toBuilder()
                .trustScore(clamp(trust))
                .intimacyScore(clamp(intimacy))
                .securityScore(clamp(security))
                .anticipationScore(clamp(anticipation))
                .build();
        return next.toBuilder()
                .phase(derivePhase(next, input.eventType() == RelationshipEventType.MISUNDERSTANDING_TRIGGERED,
                        input.eventType() == RelationshipEventType.REPAIR_SUCCESS))
                .build();
    }

    public static RelationshipPhase derivePhase(RelationshipSnapshot snapshot, boolean hasRecentInjury, boolean hasRecentRepair) {
        if (hasRecentInjury && snapshot.securityScore() <= 25) {
            return RelationshipPhase.INJURED;
        }
        if (hasRecentRepair) {
            return RelationshipPhase.REPAIRING;
        }
        if (snapshot.intimacyScore() >= 70 && snapshot.trustScore() >= 65) {
            return RelationshipPhase.STABLE_INTIMATE;
        }
        if (snapshot.intimacyScore() >= 55) {
            return RelationshipPhase.DEPENDENT;
        }
        if (snapshot.trustScore() >= 45) {
            return RelationshipPhase.FAMILIAR;
        }
        return RelationshipPhase.TESTING;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}

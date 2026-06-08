package com.lianyu.service.relationship;

import lombok.Builder;

@Builder(toBuilder = true)
public record RelationshipSnapshot(
        int trustScore,
        int intimacyScore,
        int securityScore,
        int anticipationScore,
        RelationshipPhase phase) {
}

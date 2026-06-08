package com.lianyu.service.relationship;

public record RelationshipEventInput(
        Long userId,
        Long characterId,
        Long conversationId,
        Long messageId,
        RelationshipEventType eventType,
        int weight,
        String summary) {

    public static RelationshipEventInput simple(RelationshipEventType eventType, int weight) {
        return new RelationshipEventInput(null, null, null, null, eventType, weight, null);
    }
}

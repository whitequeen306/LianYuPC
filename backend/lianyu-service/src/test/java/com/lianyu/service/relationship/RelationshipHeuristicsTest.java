package com.lianyu.service.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RelationshipHeuristicsTest {

    @Test
    void classifyUserTurn_marksDismissiveReplyForShortColdAnswers() {
        List<RelationshipEventInput> events = RelationshipHeuristics.fromUserTurn(
                7L, 9L, 11L, 13L,
                "嗯", "你刚刚认真提到难过的事", false);

        assertEquals(RelationshipEventType.USER_DISMISSIVE_RESPONSE, events.get(0).eventType());
    }

    @Test
    void assembleContext_includesPhaseLabelsAndRecentEvents() {
        RelationshipSnapshot snapshot = RelationshipSnapshot.builder()
                .trustScore(56)
                .intimacyScore(61)
                .securityScore(42)
                .anticipationScore(58)
                .phase(RelationshipPhase.FAMILIAR)
                .build();

        String context = new RelationshipContextAssembler().assemble(
                snapshot,
                List.of("用户解释了迟到原因", "你们刚约定晚安问候"));

        assertTrue(context.contains("关系阶段: familiar"));
        assertTrue(context.contains("近期关系事件"));
    }
}

package com.lianyu.service.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

}

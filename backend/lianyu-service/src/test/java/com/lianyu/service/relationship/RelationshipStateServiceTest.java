package com.lianyu.service.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.RelationshipEvent;
import com.lianyu.dao.entity.RelationshipState;
import com.lianyu.dao.mapper.RelationshipEventMapper;
import com.lianyu.dao.mapper.RelationshipStateMapper;
import java.util.List;
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

    @Test
    void buildInnerSpace_usesSnapshotAndRecentRelationshipEventsWithoutLeakingScores() {
        RelationshipStateMapper stateMapper = mock(RelationshipStateMapper.class);
        RelationshipEventMapper eventMapper = mock(RelationshipEventMapper.class);
        RelationshipState state = new RelationshipState();
        state.setUserId(3L);
        state.setCharacterId(5L);
        state.setTrustScore(82);
        state.setIntimacyScore(78);
        state.setSecurityScore(76);
        state.setAnticipationScore(81);
        state.setPhase(RelationshipPhase.STABLE_INTIMATE.name());

        RelationshipEvent event = new RelationshipEvent();
        event.setSummary("你们形成了专属称呼锚点");

        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(state);
        when(eventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(event));

        RelationshipStateService service = new RelationshipStateService(
                stateMapper,
                eventMapper,
                new RelationshipInnerSpaceAssembler());

        RelationshipInnerSpace innerSpace = service.buildInnerSpace(3L, 5L);

        assertTrue(innerSpace.headline().contains("只属于你们"));
        assertTrue(innerSpace.body().contains("默契"));
        assertFalse((innerSpace.headline() + innerSpace.body()).matches(".*\\d+.*"));
    }
}

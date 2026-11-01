package com.lianyu.service.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.RelationshipEvent;
import com.lianyu.dao.entity.RelationshipMilestone;
import com.lianyu.dao.entity.RelationshipState;
import com.lianyu.dao.mapper.RelationshipEventMapper;
import com.lianyu.dao.mapper.RelationshipMilestoneMapper;
import com.lianyu.dao.mapper.RelationshipStateMapper;
import java.time.LocalDateTime;
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

    /**
     * 里程碑必须「先查后插」：靠唯一键冲突兜底的话，用户到期后每发一句话
     * 都会打出 6~9 条失败 INSERT 和异常日志。
     */
    @Test
    void recordUserTurn_doesNotReinsertMilestonesThatAlreadyExist() {
        RelationshipStateMapper stateMapper = mock(RelationshipStateMapper.class);
        RelationshipEventMapper eventMapper = mock(RelationshipEventMapper.class);
        RelationshipMilestoneMapper milestoneMapper = mock(RelationshipMilestoneMapper.class);

        RelationshipState state = new RelationshipState();
        state.setUserId(3L);
        state.setCharacterId(5L);
        state.setTrustScore(40);
        state.setIntimacyScore(20);
        state.setSecurityScore(40);
        state.setAnticipationScore(25);
        state.setPhase(RelationshipPhase.TESTING.name());
        state.setCreatedAt(LocalDateTime.now().minusDays(20));

        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(state);
        when(eventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(milestoneMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                milestone(RelationshipMilestones.FIRST_CONTACT),
                milestone(RelationshipMilestones.FIRST_VULNERABLE),
                milestone(RelationshipMilestones.DAY_7),
                milestone(RelationshipMilestones.PHASE_FAMILIAR)));

        RelationshipStateService service = new RelationshipStateService(
                stateMapper, eventMapper, new RelationshipInnerSpaceAssembler(), milestoneMapper);
        service.recordUserTurn(3L, 5L, 11L, userMessage("今天被领导骂了，我压力好大"), List.of());

        verify(milestoneMapper, never()).insert(any(RelationshipMilestone.class));
    }

    @Test
    void recordUserTurn_recordsMissingMilestones() {
        RelationshipStateMapper stateMapper = mock(RelationshipStateMapper.class);
        RelationshipEventMapper eventMapper = mock(RelationshipEventMapper.class);
        RelationshipMilestoneMapper milestoneMapper = mock(RelationshipMilestoneMapper.class);

        RelationshipState state = new RelationshipState();
        state.setUserId(3L);
        state.setCharacterId(5L);
        state.setTrustScore(40);
        state.setIntimacyScore(20);
        state.setSecurityScore(40);
        state.setAnticipationScore(25);
        state.setPhase(RelationshipPhase.TESTING.name());
        state.setCreatedAt(LocalDateTime.now().minusDays(20));

        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(state);
        when(eventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(milestoneMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        RelationshipStateService service = new RelationshipStateService(
                stateMapper, eventMapper, new RelationshipInnerSpaceAssembler(), milestoneMapper);
        service.recordUserTurn(3L, 5L, 11L, userMessage("今天被领导骂了，我压力好大"), List.of());

        // 第一次说话 + 交心 + 满七天 + 进入熟悉，四个都该落地
        verify(milestoneMapper, atLeast(4)).insert(any(RelationshipMilestone.class));
    }

    private static Message userMessage(String content) {
        Message message = new Message();
        message.setId(1L);
        message.setRole("USER");
        message.setContent(content);
        return message;
    }

    private static RelationshipMilestone milestone(String key) {
        RelationshipMilestone milestone = new RelationshipMilestone();
        milestone.setMilestoneKey(key);
        return milestone;
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
                new RelationshipInnerSpaceAssembler(),
                mock(RelationshipMilestoneMapper.class));

        RelationshipInnerSpace innerSpace = service.buildInnerSpace(3L, 5L);

        assertTrue(innerSpace.headline().contains("只属于你们"));
        assertTrue(innerSpace.body().contains("默契"));
        assertFalse((innerSpace.headline() + innerSpace.body()).matches(".*\\d+.*"));
    }
}

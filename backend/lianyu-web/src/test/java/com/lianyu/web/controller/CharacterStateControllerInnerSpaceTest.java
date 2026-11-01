package com.lianyu.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.Result;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterState;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.service.character.CharacterDiaryService;
import com.lianyu.service.character.CharacterStateQueryService;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.relationship.RelationshipInnerSpace;
import com.lianyu.service.relationship.RelationshipPhase;
import com.lianyu.service.relationship.RelationshipPresence;
import com.lianyu.service.relationship.RelationshipSnapshot;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.storage.FileStorageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CharacterStateControllerInnerSpaceTest {

    @Test
    void listStates_includesFrontendSafeInnerSpaceFields() {
        CharacterStateService characterStateService = mock(CharacterStateService.class);
        CharacterDiaryService diaryService = mock(CharacterDiaryService.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        RelationshipStateService relationshipStateService = mock(RelationshipStateService.class);

        CharacterStateQueryService queryService = new CharacterStateQueryService(
                characterStateService,
                diaryService,
                characterMapper,
                fileStorageService,
                relationshipStateService);
        CharacterStateController controller = new CharacterStateController(queryService);

        Character character = new Character();
        character.setId(5L);
        character.setOwnerUserId(3L);
        character.setName("绫香");
        character.setAvatarUrl("avatars/ayaka.png");

        CharacterState state = new CharacterState();
        state.setCharacterId(5L);
        state.setUserId(3L);
        state.setCurrentEmotion("平静");
        state.setEmotionIntensity(50);
        state.setStatusText("一切安好。");

        when(characterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(character));
        when(characterStateService.mapForCharacters(eq(3L), eq(List.of(5L))))
                .thenReturn(Map.of(5L, state));
        when(fileStorageService.resolvePublicUrl("avatars/ayaka.png"))
                .thenReturn("https://cdn.example/avatars/ayaka.png");
        when(relationshipStateService.loadView(3L, 5L))
                .thenReturn(new RelationshipStateService.RelationshipView(
                        RelationshipSnapshot.builder()
                                .trustScore(48)
                                .intimacyScore(32)
                                .securityScore(41)
                                .anticipationScore(27)
                                .phase(RelationshipPhase.FAMILIAR)
                                .build(),
                        new RelationshipInnerSpace(
                                "她把上次那句话悄悄放在心里。",
                                "她对你仍带着一点试探，但已经开始期待下一次被认真回应。"),
                        new RelationshipPresence(12, 5, 26L, null, RelationshipPhase.DEPENDENT, 58),
                        List.of()));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(3L);

            Result<List<Map<String, Object>>> response = controller.listStates();

            Map<String, Object> item = response.getData().get(0);
            assertEquals("她把上次那句话悄悄放在心里。", item.get("innerSpaceHeadline"));
            assertEquals("她对你仍带着一点试探，但已经开始期待下一次被认真回应。", item.get("innerSpaceBody"));
            assertNotNull(item.get("statusText"));
            assertEquals("FAMILIAR", item.get("relationshipPhase"));
            assertEquals(48, item.get("relationshipTrustScore"));
            assertEquals(32, item.get("relationshipIntimacyScore"));
            assertEquals(41, item.get("relationshipSecurityScore"));
            assertEquals(27, item.get("relationshipAnticipationScore"));
            assertEquals(12, item.get("relationshipDaysKnown"));
            assertEquals(5, item.get("relationshipStreakDays"));
            assertEquals(26L, item.get("relationshipAwayHours"));
            assertEquals("DEPENDENT", item.get("relationshipNextPhase"));
            assertEquals(58, item.get("relationshipProgress"));
            assertEquals(List.of(), item.get("relationshipMilestones"));
        }
    }
}

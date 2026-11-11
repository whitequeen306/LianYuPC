package com.lianyu.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.service.ai.PetVoiceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceCallServiceTest {

    @Mock private PetVoiceRegistry petVoiceRegistry;
    @Mock private CharacterSquareTemplateMapper squareTemplateMapper;
    @InjectMocks private VoiceCallService voiceCallService;

    @Test
    void resolveVoicePetId_returnsRaidenForSquareTemplate() {
        Character character = new Character();
        character.setSourceTemplateId(7L);
        CharacterSquareTemplate template = new CharacterSquareTemplate();
        template.setSlug("raiden");
        when(squareTemplateMapper.selectById(7L)).thenReturn(template);
        when(petVoiceRegistry.hasVoice("raiden")).thenReturn(true);

        assertThat(voiceCallService.resolveVoicePetId(character)).isEqualTo("raiden");
    }

    @Test
    void resolveVoicePetId_returnsNullWhenNoMapping() {
        Character character = new Character();
        character.setSourceTemplateId(8L);
        CharacterSquareTemplate template = new CharacterSquareTemplate();
        template.setSlug("furina");
        when(squareTemplateMapper.selectById(8L)).thenReturn(template);
        when(petVoiceRegistry.hasVoice("furina")).thenReturn(false);

        assertThat(voiceCallService.resolveVoicePetId(character)).isNull();
    }

    @Test
    void callInviteIsOffByDefault() {
        Character character = new Character();
        character.setSourceTemplateId(7L);
        // 开关默认关：打扰类能力不满足条件时连音色都不必查
        assertThat(voiceCallService.trySendCallInvite(1L, 2L, character)).isNull();
    }

    @Test
    void callInviteSkipsCharacterWithoutCallVoice() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                voiceCallService, "callInviteEnabled", true);
        Character character = new Character();

        assertThat(voiceCallService.trySendCallInvite(1L, 2L, character)).isNull();
    }

    @Test
    void callInviteSkipsDoNotDisturb() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                voiceCallService, "callInviteEnabled", true);
        Character character = new Character();
        character.setSourceTemplateId(7L);
        CharacterSquareTemplate template = new CharacterSquareTemplate();
        template.setSlug("raiden");
        when(squareTemplateMapper.selectById(7L)).thenReturn(template);
        when(petVoiceRegistry.hasVoice("raiden")).thenReturn(true);
        // start == end 表示全天免打扰
        java.util.Map<String, Object> settings = new java.util.HashMap<>();
        settings.put("doNotDisturbEnabled", true);
        settings.put("dndStartMinutes", 0);
        settings.put("dndEndMinutes", 0);
        character.setSettings(settings);

        assertThat(voiceCallService.trySendCallInvite(1L, 2L, character)).isNull();
    }

    @Test
    void ttsLanguageIsUnsetByDefault() {
        // 开关默认关：返回 null，TTS 沿用配置里的 Chinese
        assertThat(voiceCallService.resolveTtsLanguage(1L)).isNull();
    }

    @Test
    void voicemailJobIsNoOpWhenDisabled() {
        // 开关默认关：连库都不该查（characterMapper 未 mock，查了就会 NPE）
        voiceCallService.processVoiceCallVoicemailJob(
                com.lianyu.service.ai.background.AiBackgroundTask
                        .voiceCallVoicemail(1L, 2L, 3L));
    }
}

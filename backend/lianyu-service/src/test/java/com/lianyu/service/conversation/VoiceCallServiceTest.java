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
}

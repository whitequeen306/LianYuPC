package com.lianyu.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.lianyu.dao.entity.Character;
import com.lianyu.service.dto.AiChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChatToolContextTest {

    @AfterEach
    void tearDown() {
        ChatToolContext.clear();
    }

    @Test
    void bindToCopiesCharacterNameAndAvatarOntoRequest() {
        Character character = new Character();
        character.setId(620L);
        character.setName(" 琉璃 ");
        character.setAvatarUrl("/api/public/files/avatars/x.png");
        AiChatRequest request = new AiChatRequest();

        ChatToolContext.bindTo(request, character);

        assertThat(request.getChatToolCharacterId()).isEqualTo(620L);
        assertThat(request.getChatToolCharacterName()).isEqualTo("琉璃");
        assertThat(request.getChatToolCharacterAvatarUrl()).isEqualTo("/api/public/files/avatars/x.png");
    }

    @Test
    void setStoresActorFieldsForDesktopControlBanner() {
        ChatToolContext.set(7L, 620L, null, null, "琉璃", "/avatars/x.png");
        ChatToolContext.Scope scope = ChatToolContext.current();
        assertThat(scope.characterName()).isEqualTo("琉璃");
        assertThat(scope.characterAvatarUrl()).isEqualTo("/avatars/x.png");
        assertThat(scope.characterId()).isEqualTo(620L);
    }
}

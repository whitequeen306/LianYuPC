package com.lianyu.service.character;

import com.lianyu.dao.entity.CharacterState;
import com.lianyu.dao.mapper.CharacterStateMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterStateServiceGetOrCreateTest {

    private final CharacterStateMapper stateMapper = Mockito.mock(CharacterStateMapper.class);
    private final CharacterStateService service = new CharacterStateService(stateMapper);

    @Test
    void getOrCreateReturnsExistingWithoutInsert() {
        CharacterState existing = new CharacterState();
        existing.setId(7L);
        existing.setCharacterId(1L);
        existing.setUserId(2L);
        when(stateMapper.selectOne(any())).thenReturn(existing);

        CharacterState result = service.getOrCreate(1L, 2L);

        assertEquals(7L, result.getId());
        verify(stateMapper, never()).upsertDefault(any());
        verify(stateMapper, never()).insert(any(CharacterState.class));
    }

    @Test
    void getOrCreateUpsertsDefaultWhenMissing() {
        CharacterState created = new CharacterState();
        created.setId(9L);
        created.setCharacterId(1L);
        created.setUserId(2L);
        when(stateMapper.selectOne(any())).thenReturn(null, created);

        CharacterState result = service.getOrCreate(1L, 2L);

        verify(stateMapper).upsertDefault(any(CharacterState.class));
        verify(stateMapper, never()).insert(any(CharacterState.class));
        assertEquals(9L, result.getId());
    }

    @Test
    void getOrCreateFallsBackToLocalInstanceWhenRowNotReadableYet() {
        when(stateMapper.selectOne(any())).thenReturn(null);

        CharacterState result = service.getOrCreate(1L, 2L);

        verify(stateMapper).upsertDefault(any(CharacterState.class));
        assertNotNull(result);
        assertEquals("平静", result.getCurrentEmotion());
    }
}

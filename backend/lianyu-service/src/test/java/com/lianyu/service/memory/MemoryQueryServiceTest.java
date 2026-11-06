package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lianyu.common.base.Result;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.MemoryEpisode;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MemoryEpisodeMapper;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

/**
 * 列表/详情字段口径：前端星图靠 memoryType 分扇区、靠 fadeLevel 画"模糊"，
 * 少一个字段就会整片退化成同一种颜色（曾经就是这样）。
 */
class MemoryQueryServiceTest {

    private MemoryMetaMapper memoryMetaMapper;
    private MemoryEpisodeMapper memoryEpisodeMapper;
    private MessageMapper messageMapper;
    private CharacterMapper characterMapper;
    private MemoryCacheService memoryCacheService;
    private MemoryQueryService service;

    @BeforeEach
    void setUp() {
        memoryMetaMapper = mock(MemoryMetaMapper.class);
        memoryEpisodeMapper = mock(MemoryEpisodeMapper.class);
        messageMapper = mock(MessageMapper.class);
        characterMapper = mock(CharacterMapper.class);
        memoryCacheService = mock(MemoryCacheService.class);
        service = new MemoryQueryService(
                memoryMetaMapper,
                memoryEpisodeMapper,
                messageMapper,
                characterMapper,
                mock(ConversationMapper.class),
                mock(MemoryWriter.class),
                memoryCacheService);
    }

    private MemoryMeta meta() {
        MemoryMeta meta = new MemoryMeta();
        meta.setId(11L);
        meta.setCharacterId(3L);
        meta.setUserId(7L);
        meta.setSummary("她说最近睡不好");
        meta.setMemoryType(MemoryType.EMOTION);
        meta.setImportance(new BigDecimal("0.80"));
        meta.setLayer("episode");
        meta.setStrength(new BigDecimal("0.640"));
        meta.setFadeLevel("faded");
        meta.setPinned(false);
        meta.setMuted(false);
        meta.setStatus("active");
        meta.setQuote("她说最近睡不好，两点还醒着");
        meta.setRevision(2);
        meta.setRecallCount(3);
        meta.setLastRecalledAt(LocalDateTime.of(2026, 11, 6, 8, 30));
        meta.setEventTime(LocalDateTime.of(2026, 11, 5, 23, 40));
        meta.setCreatedAt(LocalDateTime.of(2026, 11, 6, 9, 0));
        return meta;
    }

    @Test
    void list_exposes_fade_fields_for_star_map() {
        when(memoryMetaMapper.selectList(any())).thenReturn(List.of(meta()));
        Character character = new Character();
        character.setId(3L);
        character.setName("阿岚");
        when(characterMapper.selectBatchIds(any())).thenReturn(List.of(character));

        Result<List<Map<String, Object>>> result = service.list(7L, null, 1, 20);

        Map<String, Object> row = result.getData().get(0);
        assertEquals("EMOTION", row.get("memoryType"));
        assertEquals("faded", row.get("fadeLevel"));
        assertEquals("episode", row.get("layer"));
        assertEquals("active", row.get("status"));
        assertEquals(2, row.get("revision"));
        assertEquals("她说最近睡不好，两点还醒着", row.get("quote"));
        assertTrue(row.containsKey("strength"));
        assertTrue(row.containsKey("eventTime"));
        assertEquals(3, row.get("recallCount"));
        assertEquals("阿岚", row.get("characterName"));
    }

    @Test
    void pin_sets_zero_tau_and_clears_archive_marker() {
        when(memoryMetaMapper.selectOne(any())).thenReturn(meta());

        Result<Void> result = service.setPinned(7L, 11L, true);

        assertEquals(200, result.getCode());
        ArgumentCaptor<Wrapper<MemoryMeta>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(memoryMetaMapper).update(any(), captor.capture());
        String set = captor.getValue().getSqlSet();
        assertTrue(set.contains("tau_days"), set);
        assertTrue(set.contains("archived_at"), set);
        assertTrue(set.contains("fade_level"), set);
    }

    @Test
    void unpin_restores_default_tau_for_fact_layer() {
        when(memoryMetaMapper.selectOne(any())).thenReturn(meta());

        service.setPinned(7L, 11L, false);

        ArgumentCaptor<Wrapper<MemoryMeta>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(memoryMetaMapper).update(any(), captor.capture());
        String set = captor.getValue().getSqlSet();
        assertTrue(set.contains("tau_days"), set);
        assertTrue(!set.contains("archived_at"), set);  // 取消钉住不该顺手改归档标记
    }

    @Test
    void pin_rejects_other_users_memory() {
        when(memoryMetaMapper.selectOne(any())).thenReturn(null);

        Result<Void> result = service.setPinned(7L, 99L, true);

        assertEquals(404, result.getCode());
    }

    @Test
    void detail_uses_same_field_names_as_list() {
        when(memoryMetaMapper.selectOne(any())).thenReturn(meta());
        when(characterMapper.selectById(3L)).thenReturn(null);

        Result<Map<String, Object>> result = service.detail(7L, 11L);

        Map<String, Object> row = result.getData();
        assertEquals("EMOTION", row.get("memoryType"));
        assertEquals("faded", row.get("fadeLevel"));
        assertEquals("episode", row.get("layer"));
        assertEquals(List.of(), row.get("sourceMessages"));
    }

    @Test
    void mute_only_flips_flag_and_keeps_strength() {
        when(memoryMetaMapper.selectOne(any())).thenReturn(meta());

        Result<Void> result = service.setMuted(7L, 11L, true);

        assertEquals(200, result.getCode());
        ArgumentCaptor<Wrapper<MemoryMeta>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(memoryMetaMapper).update(any(), captor.capture());
        String set = captor.getValue().getSqlSet();
        assertTrue(set.contains("muted"), set);
        assertFalse(set.contains("strength"), set);
        assertFalse(set.contains("tau_days"), set);
        org.mockito.Mockito.verify(memoryCacheService).invalidate(7L, 3L);
    }

    @Test
    void mute_rejects_other_users_memory() {
        when(memoryMetaMapper.selectOne(any())).thenReturn(null);

        Result<Void> result = service.setMuted(7L, 11L, true);

        assertEquals(404, result.getCode());
    }

    @Test
    void episodes_carry_quote_and_character_name() {
        MemoryEpisode episode = new MemoryEpisode();
        episode.setId(9L);
        episode.setCharacterId(3L);
        episode.setUserId(7L);
        episode.setSummary("搬家那天");
        episode.setQuote("我明天要搬家了");
        episode.setEventTime(LocalDateTime.of(2026, 11, 1, 22, 0));
        when(memoryEpisodeMapper.selectList(any())).thenReturn(List.of(episode));

        Character character = new Character();
        character.setId(3L);
        character.setName("阿岚");
        when(characterMapper.selectBatchIds(any())).thenReturn(List.of(character));

        Result<List<Map<String, Object>>> result = service.episodes(7L, null, 50);

        assertEquals(200, result.getCode());
        Map<String, Object> row = result.getData().get(0);
        assertEquals("我明天要搬家了", row.get("quote"));
        assertEquals("阿岚", row.get("characterName"));
        assertEquals(LocalDateTime.of(2026, 11, 1, 22, 0), row.get("eventTime"));
    }
}

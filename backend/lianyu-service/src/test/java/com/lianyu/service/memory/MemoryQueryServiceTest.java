package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.common.base.Result;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 列表/详情字段口径：前端星图靠 memoryType 分扇区、靠 fadeLevel 画"模糊"，
 * 少一个字段就会整片退化成同一种颜色（曾经就是这样）。
 */
class MemoryQueryServiceTest {

    private MemoryMetaMapper memoryMetaMapper;
    private MessageMapper messageMapper;
    private CharacterMapper characterMapper;
    private MemoryQueryService service;

    @BeforeEach
    void setUp() {
        memoryMetaMapper = mock(MemoryMetaMapper.class);
        messageMapper = mock(MessageMapper.class);
        characterMapper = mock(CharacterMapper.class);
        service = new MemoryQueryService(
                memoryMetaMapper,
                messageMapper,
                characterMapper,
                mock(ConversationMapper.class),
                mock(MemoryWriter.class),
                mock(MemoryCacheService.class));
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
        assertEquals("阿岚", row.get("characterName"));
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
}

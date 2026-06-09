package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MemoryWriterRelationshipTest {

    private final MemoryExtractionHeuristics heuristics = new MemoryExtractionHeuristics();

    @Test
    void extractRelationshipMemories_marksNicknameAsRitual() {
        Message msg = new Message();
        msg.setId(101L);
        msg.setRole("USER");
        msg.setContent("以后你可以叫我阿昼，这是只给你叫的。");

        List<MemoryExtractionHeuristics.HeuristicMemory> candidates =
                heuristics.extractRelationshipMemories(List.of(msg));

        assertEquals(MemoryType.RITUAL, candidates.get(0).memoryType());
        assertTrue(candidates.get(0).summary().contains("专属称呼"));
    }

    @Test
    void retrieveProfileContext_includesRelationBlockBeforePromptReturn() {
        MemoryMetaMapper memoryMetaMapper = Mockito.mock(MemoryMetaMapper.class);
        MemoryCacheService memoryCacheService = Mockito.mock(MemoryCacheService.class);
        MemoryRetriever retriever = new MemoryRetriever(
                null, null, memoryMetaMapper, memoryCacheService);

        MemoryMeta fact = new MemoryMeta();
        fact.setId(1L);
        fact.setSummary("【长期记忆/爱好】夜跑");
        fact.setMemoryType(MemoryType.FACT);
        fact.setImportance(new java.math.BigDecimal("0.80"));

        MemoryMeta ritual = new MemoryMeta();
        ritual.setId(2L);
        ritual.setSummary("你们约定了晚安问候");
        ritual.setMemoryType(MemoryType.RITUAL);
        ritual.setImportance(new java.math.BigDecimal("0.70"));

        MemoryMeta relation = new MemoryMeta();
        relation.setId(3L);
        relation.setSummary("上次因为敷衍回复让她有些受伤");
        relation.setMemoryType(MemoryType.RELATION);
        relation.setImportance(new java.math.BigDecimal("0.70"));

        when(memoryCacheService.getRecentRows(any(), any())).thenReturn(null);
        when(memoryCacheService.getProfileFacts(any(), any())).thenReturn(null);
        when(memoryMetaMapper.selectList(any())).thenReturn(List.of(fact, ritual, relation));

        String context = retriever.retrieveProfileContext(5L, 3L);

        assertTrue(context.contains("[关系事件]"));
        assertTrue(context.contains("[专属仪式]"));
    }
}

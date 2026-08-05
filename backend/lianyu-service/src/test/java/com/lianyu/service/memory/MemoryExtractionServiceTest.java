package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.Message;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.service.memory.MemoryWriter.MemorySummaryTask;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemoryExtractionServiceTest {

    private MemoryExtractionHeuristics heuristics;
    private MemoryLlmExtractor llmExtractor;
    private MemoryExtractionService service;

    @BeforeEach
    void setUp() {
        heuristics = new MemoryExtractionHeuristics();
        llmExtractor = mock(MemoryLlmExtractor.class);
        service = new MemoryExtractionService(heuristics, llmExtractor);
        ReflectionTestUtils.setField(service, "extractionEnabled", true);
        ReflectionTestUtils.setField(service, "importanceThreshold", 0.5);
        ReflectionTestUtils.setField(service, "maxMemoriesPerTurn", 3);
    }

    @Test
    void extract_usesRegexForProfileFact() {
        when(llmExtractor.extract(any(), anyList())).thenReturn(List.of());

        Message msg = new Message();
        msg.setId(10L);
        msg.setRole("USER");
        msg.setContent("我叫小明，我喜欢夜跑");

        List<ExtractedMemory> result = service.extract(List.of(msg), new MemorySummaryTask(1L, 2L, 3L, "p", null));

        assertTrue(result.stream().anyMatch(m -> m.summary().contains("【长期记忆/姓名】小明")));
        assertTrue(result.stream().anyMatch(m -> m.summary().contains("【长期记忆/爱好】夜跑")));
    }

    @Test
    void extract_nameVariants_writeStructuredProfile() {
        when(llmExtractor.extract(any(), anyList())).thenReturn(List.of());

        assertNameExtracted("请叫我小明", "【长期记忆/姓名】小明");
        assertNameExtracted("my name is Bob", "【长期记忆/姓名】Bob");
        assertNameExtracted("我叫 小明", "【长期记忆/姓名】小明");
    }

    @Test
    void extract_nameFact_notOverwrittenByKeywordMemory() {
        when(llmExtractor.extract(any(), anyList())).thenReturn(List.of());

        Message msg = new Message();
        msg.setId(20L);
        msg.setRole("USER");
        msg.setContent("我叫小明");

        List<ExtractedMemory> result = service.extract(List.of(msg), new MemorySummaryTask(1L, 2L, 3L, "p", null));

        long nameMatches = result.stream()
                .filter(m -> m.summary().contains("小明"))
                .count();
        assertEquals(1, nameMatches);
        assertTrue(result.stream().anyMatch(m -> "【长期记忆/姓名】小明".equals(m.summary())));
        assertTrue(result.stream().noneMatch(m -> "我叫小明".equals(m.summary())));
    }

    @Test
    void extract_ritualNickname_alsoWritesName() {
        when(llmExtractor.extract(any(), anyList())).thenReturn(List.of());

        Message msg = new Message();
        msg.setId(21L);
        msg.setRole("USER");
        msg.setContent("以后你可以叫我阿昼");

        List<ExtractedMemory> result = service.extract(List.of(msg), new MemorySummaryTask(1L, 2L, 3L, "p", null));

        assertTrue(result.stream().anyMatch(m -> "【长期记忆/姓名】阿昼".equals(m.summary())));
        assertTrue(result.stream().anyMatch(m -> "你们形成了专属称呼锚点".equals(m.summary())));
    }

    private void assertNameExtracted(String content, String expectedSummary) {
        Message msg = new Message();
        msg.setId(30L);
        msg.setRole("USER");
        msg.setContent(content);

        List<ExtractedMemory> result = service.extract(List.of(msg), new MemorySummaryTask(1L, 2L, 3L, "p", null));
        assertTrue(result.stream().anyMatch(m -> expectedSummary.equals(m.summary())), content);
    }

    @Test
    void extract_filtersLowImportanceFromLlm() {
        when(llmExtractor.extract(any(MemorySummaryTask.class), anyList())).thenReturn(List.of(
                new ExtractedMemory("临时琐事", MemoryType.FACT, 11L, 0.2)));

        Message msg = new Message();
        msg.setId(11L);
        msg.setRole("USER");
        msg.setContent("嗯嗯好的");

        List<ExtractedMemory> result = service.extract(List.of(msg), new MemorySummaryTask(1L, 2L, 3L, "p", null));

        assertEquals(0, result.size());
    }

    @Test
    void extract_rejectsQuestionLikeProfileValue() {
        when(llmExtractor.extract(any(), anyList())).thenReturn(List.of());

        Message msg = new Message();
        msg.setId(12L);
        msg.setRole("USER");
        msg.setContent("我叫什么来着？");

        List<ExtractedMemory> result = service.extract(List.of(msg), new MemorySummaryTask(1L, 2L, 3L, "p", null));

        assertTrue(result.stream().noneMatch(m -> m.summary().contains("什么")));
    }
}

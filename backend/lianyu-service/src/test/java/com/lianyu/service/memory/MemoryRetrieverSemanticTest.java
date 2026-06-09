package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.service.ai.RerankerService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryRetrieverSemanticTest {

    @Test
    void searchSemantic_usesMilvusSummaryWithoutMysqlLookup() throws Exception {
        RerankerService rerankerService = mock(RerankerService.class);
        MemoryVectorStore vectorStore = mock(MemoryVectorStore.class);
        MemoryMetaMapper memoryMetaMapper = mock(MemoryMetaMapper.class);
        MemoryCacheService cacheService = mock(MemoryCacheService.class);

        when(cacheService.getSemanticResults(any(), any(), anyString())).thenReturn(null);
        when(vectorStore.search(any(), any(), anyString(), anyInt(), any(Float.class)))
                .thenReturn(List.of(new MemoryVectorStore.VectorHit("【长期记忆/爱好】夜跑", 0.9f)));
        when(rerankerService.rerank(anyString(), anyList(), any()))
                .thenReturn(List.of(new RerankerService.ScoredDoc(0, "【长期记忆/爱好】夜跑", 0.9f)));

        MemoryRetriever retriever = new MemoryRetriever(
                rerankerService, vectorStore, memoryMetaMapper, cacheService);

        List<String> results = retriever.searchSemantic(2L, 3L, "夜跑", 3);

        assertEquals(1, results.size());
        assertEquals("- 【长期记忆/爱好】夜跑", results.get(0));
    }

    @Test
    void searchSemantic_fallsBackToMysqlWhenMilvusEmpty() throws Exception {
        RerankerService rerankerService = mock(RerankerService.class);
        MemoryVectorStore vectorStore = mock(MemoryVectorStore.class);
        MemoryMetaMapper memoryMetaMapper = mock(MemoryMetaMapper.class);
        MemoryCacheService cacheService = mock(MemoryCacheService.class);

        when(cacheService.getSemanticResults(any(), any(), anyString())).thenReturn(null);
        when(vectorStore.search(any(), any(), anyString(), anyInt(), any(Float.class)))
                .thenReturn(List.of());

        MemoryMeta meta = new MemoryMeta();
        meta.setId(9L);
        meta.setSummary("用户提到最近工作压力大");
        meta.setMemoryType(MemoryType.EMOTION);
        meta.setImportance(new BigDecimal("0.70"));

        when(cacheService.getRecentRows(any(), any())).thenReturn(null);
        when(cacheService.getProfileFacts(any(), any())).thenReturn(null);
        when(memoryMetaMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(meta));
        when(rerankerService.rerank(anyString(), anyList(), any()))
                .thenReturn(List.of(new RerankerService.ScoredDoc(0, meta.getSummary(), 0.8f)));

        MemoryRetriever retriever = new MemoryRetriever(
                rerankerService, vectorStore, memoryMetaMapper, cacheService);

        List<String> results = retriever.searchSemantic(2L, 3L, "工作", 3);

        assertEquals(1, results.size());
        assertTrueContains(results.get(0), "工作压力大");
    }

    private void assertTrueContains(String actual, String fragment) {
        org.junit.jupiter.api.Assertions.assertTrue(actual.contains(fragment));
    }
}

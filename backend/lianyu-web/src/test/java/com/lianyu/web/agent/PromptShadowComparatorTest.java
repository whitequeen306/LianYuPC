package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.agent.AssemblySnapshot;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * prompt 级漂移比对测试。
 *
 * <p>核心约束：比对**不能影响用户请求** —— Python 不可用、返回错误、抛异常时
 * 都必须安静地放弃，只留 debug 日志。另外日志里不能出现 prompt 原文。
 */
class PromptShadowComparatorTest {

    private AgentRuntimeClient client;
    private AgentShadowStats stats;
    private PromptShadowComparator comparator;
    private AssemblySnapshot snapshot;

    @BeforeEach
    void setUp() {
        client = mock(AgentRuntimeClient.class);
        stats = new AgentShadowStats();
        comparator = new PromptShadowComparator(client, stats);
        snapshot = new AssemblySnapshot("小林", Map.of("personality", "温柔"), null,
                "记忆块", "工具提示", "情绪块", "zh", true, true, 2,
                true, "时间事实", "上海", "full");
    }

    @Test
    void compareEqualPrompts() {
        PromptShadowComparator.Diff diff = PromptShadowComparator.compare("abc", "abc");
        assertTrue(diff.equal());
        assertEquals(3, diff.javaLength());
        assertEquals(-1, diff.firstDiffAt());
        assertTrue(diff.describe().startsWith("match"));
    }

    @Test
    void compareReportsFirstDiffPosition() {
        PromptShadowComparator.Diff diff = PromptShadowComparator.compare("abcdef", "abXdef");
        assertFalse(diff.equal());
        assertEquals(2, diff.firstDiffAt());
        assertTrue(diff.describe().contains("at=2"));
    }

    @Test
    void compareHandlesLengthDifference() {
        PromptShadowComparator.Diff diff = PromptShadowComparator.compare("abc", "abcdef");
        assertFalse(diff.equal());
        assertEquals(3, diff.javaLength());
        assertEquals(6, diff.pythonLength());
        assertEquals(3, diff.firstDiffAt(), "共同前缀长度即首处差异位置");
    }

    @Test
    void compareHandlesNulls() {
        assertTrue(PromptShadowComparator.compare(null, null).equal());
        assertFalse(PromptShadowComparator.compare(null, "x").equal());
    }

    @Test
    void matchIsRecordedWithoutContent() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"systemPrompt\":\"相同内容\"}");
        when(client.readTree(anyString())).thenAnswer(inv -> {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readTree((String) inv.getArgument(0));
        });

        PromptShadowComparator.Diff diff = comparator.compareWithAgent(7L, snapshot, "相同内容");

        assertTrue(diff.equal());
        assertTrue(stats.snapshot().get("prompt").contains("match=1"));
    }

    @Test
    void diffIsRecordedWhenPromptsDiffer() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"systemPrompt\":\"内容A\"}");
        when(client.readTree(anyString())).thenAnswer(inv -> {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readTree((String) inv.getArgument(0));
        });

        PromptShadowComparator.Diff diff = comparator.compareWithAgent(7L, snapshot, "内容B");

        assertFalse(diff.equal());
        assertTrue(stats.snapshot().get("prompt").contains("diff=1"));
    }

    @Test
    void agentErrorDoesNotRecordAndDoesNotThrow() {
        when(client.postJson(anyString(), anyLong(), anyMap()))
                .thenReturn("{\"error\":\"assemble_failed\"}");
        when(client.readTree(anyString())).thenAnswer(inv -> {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readTree((String) inv.getArgument(0));
        });

        assertNull(comparator.compareWithAgent(7L, snapshot, "任意"));
        assertTrue(stats.snapshot().isEmpty(), "失败不应污染漂移率统计");
    }

    @Test
    void nullResponseIsIgnored() {
        when(client.postJson(anyString(), anyLong(), anyMap())).thenReturn(null);
        assertNull(comparator.compareWithAgent(7L, snapshot, "任意"));
        assertTrue(stats.snapshot().isEmpty());
    }

    @Test
    void nullJavaPromptSkipsCall() {
        assertNull(comparator.compareWithAgent(7L, snapshot, null));
        verify(client, never()).postJson(anyString(), anyLong(), anyMap());
    }

    @Test
    void snapshotBodyUsesPythonAliases() {
        Map<String, Object> body = snapshot.toBody();
        assertTrue(body.containsKey("characterName"));
        assertTrue(body.containsKey("memoryContext"));
        assertTrue(body.containsKey("afterBase"));
        assertEquals("full", body.get("afterBase"));
    }
}

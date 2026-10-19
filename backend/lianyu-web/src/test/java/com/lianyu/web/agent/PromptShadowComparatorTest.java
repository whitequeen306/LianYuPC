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
                true, "时间事实", "上海", "full", false, null, null, null, null, null, null);
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

    // ── 易变片段掩码 ──

    @Test
    void maskHidesTimeFactLines() {
        String prompt = "前缀\n当前真实时间：2026-10-18 15:00:00（周六）\n当前时段：下午（13:00-18:00）\n后缀";
        String masked = PromptShadowComparator.maskVolatile(prompt);
        assertTrue(masked.contains("当前真实时间：<masked>"));
        assertTrue(masked.contains("当前时段：<masked>"));
        assertFalse(masked.contains("15:00:00"));
        assertTrue(masked.contains("前缀") && masked.contains("后缀"));
    }

    @Test
    void timeDriftAloneIsNotADiff() {
        String javaPrompt = "A\n当前真实时间：2026-10-18 15:00:00（周六）\n当前时段：下午\nB";
        String pythonPrompt = "A\n当前真实时间：2026-10-18 15:01:02（周六）\n当前时段：下午\nB";
        assertTrue(PromptShadowComparator.compare(javaPrompt, pythonPrompt).equal(),
                "仅时间不同不应算漂移（两边各自读时钟）");
    }

    @Test
    void structuralDiffStillDetectedAfterMasking() {
        String javaPrompt = "A\n当前真实时间：2026-10-18 15:00:00\nB";
        String pythonPrompt = "A\n当前真实时间：2026-10-18 15:00:00\nC";
        assertFalse(PromptShadowComparator.compare(javaPrompt, pythonPrompt).equal());
    }

    @Test
    void maskHandlesNullAndEmpty() {
        assertEquals("", PromptShadowComparator.maskVolatile(null));
        assertEquals("", PromptShadowComparator.maskVolatile(""));
    }

    @Test
    void lengthsAreReportedForComparedText() {
        // 长度与差异位置都基于「掩码后」的文本 —— 掩码才是实际比较对象
        String javaPrompt = "A\n当前真实时间：2026-10-18 15:00:00\nC";
        String pythonPrompt = "A\n当前真实时间：2026-10-18 15:01:00\nC";
        PromptShadowComparator.Diff diff = PromptShadowComparator.compare(javaPrompt, pythonPrompt);
        assertEquals(PromptShadowComparator.maskVolatile(javaPrompt).length(), diff.javaLength());
        assertTrue(diff.equal(), "时间不同但掩码后相同 → 不算漂移");
    }

    // ── 分段差异定位 ──

    @Test
    void segmentsAlignByHeaderAndFlagDifferentBlock() {
        String javaPrompt = "preamble text\n=== 关于用户的记忆 ===\n记忆A\n=== 当前情绪状态 ===\n开心\n";
        String pythonPrompt = "preamble text\n=== 关于用户的记忆 ===\n记忆A\n=== 当前情绪状态 ===\n难过\n";
        var segments = PromptShadowComparator.segmentDiff(javaPrompt, pythonPrompt);

        assertEquals(3, segments.size(), "preamble + 两个 === 段");
        assertTrue(segments.get(0).equal());
        assertTrue(segments.get(1).equal(), "记忆段相同");
        assertFalse(segments.get(2).equal(), "情绪段应被标记为不同");
        assertTrue(segments.get(2).name().contains("当前情绪状态"));
    }

    @Test
    void segmentsHandleBracketHeaders() {
        String javaPrompt = "[用户画像]\n- a\n[近期情绪线索]\n- b\n";
        String pythonPrompt = "[用户画像]\n- a\n[近期情绪线索]\n- c\n";
        var segments = PromptShadowComparator.segmentDiff(javaPrompt, pythonPrompt);
        assertTrue(segments.get(0).equal());
        assertTrue(segments.get(1).equal());
        assertFalse(segments.get(2).equal());
    }

    @Test
    void segmentsReportMissingBlock() {
        String javaPrompt = "base\n=== 当前真实环境 ===\ntime\n";
        String pythonPrompt = "base\n";
        var segments = PromptShadowComparator.segmentDiff(javaPrompt, pythonPrompt);
        assertFalse(segments.get(1).equal());
        assertEquals(0, segments.get(1).pythonChars());
    }

    @Test
    void segmentsEqualForIdenticalPrompts() {
        String prompt = "a\n=== x ===\nb\n";
        assertTrue(PromptShadowComparator.segmentDiff(prompt, prompt).stream()
                .allMatch(PromptShadowComparator.SegmentDiff::equal));
    }

    @Test
    void segmentDescribeHasNoPromptBody() {
        String javaPrompt = "base\n=== x ===\nsecret java body\n";
        String pythonPrompt = "base\n=== x ===\nsecret py body\n";
        var segment = PromptShadowComparator.segmentDiff(javaPrompt, pythonPrompt).get(1);
        assertFalse(segment.describe().contains("secret"), "日志不得包含 prompt 正文");
    }
}

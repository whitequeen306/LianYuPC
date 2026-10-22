package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 句子切分 + 按句流水线的契约测试。
 *
 * <p>2026-10-22 从 {@code VoiceCallDuplexSessionTest} 迁过来：切分逻辑已经搬到
 * {@link SentenceSplitter}，因为语音通话改成了「LLM 还在吐字时就切句、切出来就合成」。
 * 原来只能测静态的 {@code nextCommitEnd}（切点下标）；现在可以直接测
 * 「喂增量 → 立刻拿到可合成的句子」这个真正影响体感的行为。
 */
class SentenceSplitterTest {

    // ── 切点规则（沿用旧断言，防止回归）──

    @Test
    void markerIsHighestPriorityCutPoint() {
        assertEquals(6, SentenceSplitter.nextCommitEnd("要不要我陪你<|pause|>还是先休息", true));
        assertEquals(1, SentenceSplitter.nextCommitEnd("嗯<|pause|>我在听", false));
    }

    @Test
    void firstCommitMergesTinyLeadingSentence() {
        assertEquals(9, SentenceSplitter.nextCommitEnd("好。今晚想吃什么？", false));
        assertEquals(5, SentenceSplitter.nextCommitEnd("我在听呢。后面还有", false));
        assertEquals(-1, SentenceSplitter.nextCommitEnd("好。今", false));
    }

    @Test
    void shortSingleSentenceWaitsForFlush() {
        assertEquals(-1, SentenceSplitter.nextCommitEnd("嗯，我在听", false));
        assertEquals(-1, SentenceSplitter.nextCommitEnd("嗯，我在听呢", false));
    }

    @Test
    void laterCommitsCutAtSentenceEndOnly() {
        assertEquals(2, SentenceSplitter.nextCommitEnd("嗯。走", true));
        assertEquals(-1, SentenceSplitter.nextCommitEnd("今天天气不错", true));
        // 逗号不算切点
        assertEquals(-1, SentenceSplitter.nextCommitEnd("今天天气不错，想出去走走", true));
        assertEquals(-1, SentenceSplitter.nextCommitEnd("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", true));
    }

    @Test
    void incompleteMarkerPrefixIsNeverSplit() {
        assertEquals(9, SentenceSplitter.nextCommitEnd("今天天气不错要不要<|", false));
        assertEquals(-1, SentenceSplitter.nextCommitEnd("<|", false));
    }

    @Test
    void stripMarkersForPersistAndFlush() {
        assertEquals("好。走吧", SentenceSplitter.stripPauseMarkers("好。<|pause|>走吧"));
        assertEquals("走吧", SentenceSplitter.stripPauseMarkers("走吧<|"));
        assertEquals("", SentenceSplitter.stripPauseMarkers(null));
    }

    // ── 流水线行为：边收边切 ──

    @Test
    void yieldsSentenceAsSoonAsEnderArrives() {
        SentenceSplitter splitter = new SentenceSplitter();
        // 第一次增量还没到切点
        assertTrue(splitter.accept("我在听呢").isEmpty());
        // 句号一来就立刻切出这一句 —— 不必等后面的话
        List<String> first = splitter.accept("。");
        assertEquals(List.of("我在听呢。"), first);
        // 后续增量独立成句
        assertEquals(List.of("后面还有一句。"), splitter.accept("后面还有一句。"));
    }

    @Test
    void markerSplitsImmediatelyAndIsStripped() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertEquals(List.of("要不要我陪你"), splitter.accept("要不要我陪你<|pause|>"));
    }

    @Test
    void doesNotCutInsideInnerThoughtParens() {
        SentenceSplitter splitter = new SentenceSplitter();
        // 括号里的句号不能作为切点，否则逐句剥离括号后会念出残句
        assertTrue(splitter.accept("（他笑了。真好）我在").isEmpty());
        // 括号闭合后的句号才是合法切点，且括号内容被一并剥掉
        assertEquals(List.of("我在。"), splitter.accept("。"));
    }

    @Test
    void flushEmitsRemainderAndResets() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertTrue(splitter.accept("嗯，我在听").isEmpty());
        assertEquals(List.of("嗯，我在听"), splitter.flush());
        // flush 之后再喂，仍按「首次切分」规则处理
        assertTrue(splitter.accept("好").isEmpty());
        assertEquals(List.of("好"), splitter.flush());
    }

    @Test
    void blankOrMarkerOnlyTextIsDropped() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertTrue(splitter.accept("<|pause|>").isEmpty());
        assertTrue(splitter.accept("（只有内心独白。）").isEmpty());
        assertTrue(splitter.flush().isEmpty());
    }

    @Test
    void insideParensHandlesBothWidths() {
        assertTrue(SentenceSplitter.insideParens("（abc", 3));
        assertTrue(SentenceSplitter.insideParens("(abc", 3));
        assertEquals(false, SentenceSplitter.insideParens("（abc）", 5));
        assertEquals(false, SentenceSplitter.insideParens("abc", 2));
    }
}

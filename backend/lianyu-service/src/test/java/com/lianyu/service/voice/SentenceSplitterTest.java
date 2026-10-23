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
 * 「喂增量 → 立刻拿到可合成的片段」这个真正影响体感的行为。
 */
class SentenceSplitterTest {

    private static List<String> texts(List<SentenceSplitter.Segment> segments) {
        return segments.stream().map(SentenceSplitter.Segment::text).toList();
    }

    private static List<Integer> pauses(List<SentenceSplitter.Segment> segments) {
        return segments.stream().map(SentenceSplitter.Segment::pauseAfterMs).toList();
    }

    // ── 切点规则 ──

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

    /**
     * 首句之后要求攒够 {@link SentenceSplitter#MIN_LATER_COMMIT} 字再切。
     *
     * <p>旧行为是「首句之后每遇到句号就切」，实测把一段话切成 2-4 个独立合成单元，
     * 句间停顿从模型自然的 585ms 掉到 304ms，且每句语调重新起头 —— 用户反馈的
     * 「该停顿的地方连读」。这条断言就是那次行为变更的锚点。
     */
    @Test
    void afterFirstCommitShortSentencesAreMergedNotSplit() {
        // 「嗯。走」只有 4 字：首句之后不再切，等更多内容
        assertEquals(-1, SentenceSplitter.nextCommitEnd("嗯。走", true));
        assertEquals(-1, SentenceSplitter.nextCommitEnd("后面还有一句。", true));
        // 刚好凑到门槛就在这个句号处切
        String filler = "一".repeat(SentenceSplitter.MIN_LATER_COMMIT);
        assertEquals(SentenceSplitter.MIN_LATER_COMMIT + 1,
                SentenceSplitter.nextCommitEnd(filler + "。", true));
        // 差一个字就不切（门槛是硬的，不能靠估算）
        assertEquals(-1, SentenceSplitter.nextCommitEnd(filler.substring(1) + "。", true));
    }

    @Test
    void laterCommitsNeverCutAtComma() {
        assertEquals(-1, SentenceSplitter.nextCommitEnd("今天天气不错，想出去走走", true));
        assertEquals(-1, SentenceSplitter.nextCommitEnd("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", true));
    }

    /**
     * 首片允许在逗号上切 —— 这是**首声延迟**的关键一刀。
     *
     * <p>首声 = LLM 出第一个字（0.8-1.3s）+ 等句号 + TTS 首包（~0.9s）。若模型开头是个长句，
     * "等句号"能吃掉 0.5-1.5s。中文口语里逗号本身是自然气口，8 字之后切开听感损失很小。
     * 只对首片开：后续片段已经合并成大片，在那里按逗号切会把停顿切碎。
     */
    @Test
    void firstUnitMayCutAtCommaToStartAudioEarlier() {
        // 8 字之后的逗号 → 切（早开口）
        assertEquals(10, SentenceSplitter.nextCommitEnd("嗯，我知道了这件事，后面还有话。", false));
        // 8 字以内只有逗号 → 不切（避免「嗯，好，」这种碎片）
        assertEquals(-1, SentenceSplitter.nextCommitEnd("嗯，好，我知道了", false));
        // 句号比逗号更早 → 认句号（不强求逗号）
        assertEquals(5, SentenceSplitter.nextCommitEnd("我知道了。后面还有很多，话要说。", false));
        // 首片之后一律不认逗号
        assertEquals(-1, SentenceSplitter.nextCommitEnd("嗯，我知道了这件事，后面还有话。", true));
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
        assertTrue(splitter.accept("我在听呢").isEmpty());
        // 句号一来就立刻切出这一句 —— 首句不等后面的话（首包延迟优先）
        assertEquals(List.of("我在听呢。"), texts(splitter.accept("。")));
    }

    @Test
    void laterSentenceIsHeldUntilLongEnough() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertEquals(List.of("我在听呢。"), texts(splitter.accept("我在听呢。")));
        // 首句之后：短句不切，等攒够再一起合成
        assertTrue(splitter.accept("后面还有一句。").isEmpty());
        assertTrue(splitter.accept("再加一句。").isEmpty());
        // 攒够了就在下一个句号处切，且切出来的一片明显长于门槛
        String filler = "凑".repeat(SentenceSplitter.MIN_LATER_COMMIT);
        List<String> got = texts(splitter.accept(filler + "。"));
        assertEquals(1, got.size());
        assertTrue(got.get(0).length() > SentenceSplitter.MIN_LATER_COMMIT);
    }

    @Test
    void markerSplitsImmediatelyAndIsStripped() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertEquals(List.of("要不要我陪你"), texts(splitter.accept("要不要我陪你<|pause|>")));
    }

    /**
     * 回归：切在 marker 处时 marker 会留在缓冲区头部。
     * 若不主动清掉，{@code indexOf == 0} 分支会让这一回合**再也不切**，
     * 后续内容全部堆到 flush 才合成（线上表现为「一句话卡到最后一口气才出声」）。
     */
    @Test
    void leadingMarkerDoesNotBlockLaterSplits() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertEquals(List.of("先说一句。"), texts(splitter.accept("先说一句。<|pause|>")));
        // marker 现在位于缓冲区开头；再喂内容必须还能切
        assertTrue(splitter.accept("后面这一句也够长了吧。").isEmpty());
        String filler = "凑".repeat(SentenceSplitter.MIN_LATER_COMMIT);
        List<String> got = texts(splitter.accept(filler + "。"));
        assertEquals(1, got.size());
    }

    @Test
    void doesNotCutInsideInnerThoughtParens() {
        SentenceSplitter splitter = new SentenceSplitter();
        // 括号里的句号不能作为切点，否则逐句剥离括号后会念出残句
        assertTrue(splitter.accept("（他笑了。真好）我在").isEmpty());
        // 括号闭合后的句号才是合法切点，且括号内容被一并剥掉
        assertEquals(List.of("我在。"), texts(splitter.accept("。")));
    }

    @Test
    void flushEmitsRemainderAndResets() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertTrue(splitter.accept("嗯，我在听").isEmpty());
        assertEquals(List.of("嗯，我在听"), texts(splitter.flush()));
        // flush 之后再喂，仍按「首次切分」规则处理
        assertTrue(splitter.accept("好").isEmpty());
        assertEquals(List.of("好"), texts(splitter.flush()));
    }

    @Test
    void blankOrMarkerOnlyTextIsDropped() {
        SentenceSplitter splitter = new SentenceSplitter();
        assertTrue(splitter.accept("<|pause|>").isEmpty());
        assertTrue(splitter.accept("（只有内心独白。）").isEmpty());
        assertTrue(splitter.flush().isEmpty());
    }

    // ── 停顿时长 ──

    /**
     * 停顿长度不是随手写的常数，而是实测标定的：
     * 模型在同一次合成里句号停顿 585ms，而两句独立合成拼起来只有 304ms，
     * 差值 ≈ 280ms 就是要补的静音；现在按标点分级，省略号/破折号最长。
     */
    @Test
    void pauseAfterEndingFollowsPunctuation() {
        assertEquals(SentenceSplitter.SENTENCE_PAUSE_MS, SentenceSplitter.pauseForEnding("我回来了。"));
        // 按换行切开时，换行符会留在前一片的末尾 → 按句号级停顿
        assertEquals(SentenceSplitter.SENTENCE_PAUSE_MS, SentenceSplitter.pauseForEnding("第一段\n"));
        assertEquals(SentenceSplitter.EXCLAIM_PAUSE_MS, SentenceSplitter.pauseForEnding("真的吗？"));
        assertEquals(SentenceSplitter.EXCLAIM_PAUSE_MS, SentenceSplitter.pauseForEnding("太好了！"));
        assertEquals(SentenceSplitter.ELLIPSIS_PAUSE_MS, SentenceSplitter.pauseForEnding("这个嘛……"));
        assertEquals(SentenceSplitter.ELLIPSIS_PAUSE_MS, SentenceSplitter.pauseForEnding("我想想——"));
        assertEquals(SentenceSplitter.CLAUSE_PAUSE_MS, SentenceSplitter.pauseForEnding("第一点；"));
        assertEquals(SentenceSplitter.SOFT_PAUSE_MS, SentenceSplitter.pauseForEnding("然后，"));
        assertEquals(0, SentenceSplitter.pauseForEnding(""));
        // 尾随空格不影响判断
        assertEquals(SentenceSplitter.SENTENCE_PAUSE_MS, SentenceSplitter.pauseForEnding("我回来了。 "));
        // 语气收束比陈述更久
        assertTrue(SentenceSplitter.EXCLAIM_PAUSE_MS > SentenceSplitter.SENTENCE_PAUSE_MS);
        assertTrue(SentenceSplitter.ELLIPSIS_PAUSE_MS > SentenceSplitter.EXCLAIM_PAUSE_MS);
    }

    @Test
    void markerCutGetsLongerPause() {
        SentenceSplitter splitter = new SentenceSplitter();
        List<SentenceSplitter.Segment> seg = splitter.accept("慢慢来<|pause|>");
        assertEquals(List.of("慢慢来"), texts(seg));
        assertEquals(List.of(SentenceSplitter.PAUSE_MARKER_MS), pauses(seg));
    }

    @Test
    void flushSegmentCarriesNoTrailingPause() {
        // 回合最后一片之后再插静音只会让 turn.done 变慢，没有任何听感收益
        SentenceSplitter splitter = new SentenceSplitter();
        assertTrue(splitter.accept("嗯，我在听").isEmpty());
        assertEquals(List.of(0), pauses(splitter.flush()));
    }

    @Test
    void insideParensHandlesBothWidths() {
        assertTrue(SentenceSplitter.insideParens("（abc", 3));
        assertTrue(SentenceSplitter.insideParens("(abc", 3));
        assertEquals(false, SentenceSplitter.insideParens("（abc）", 5));
        assertEquals(false, SentenceSplitter.insideParens("abc", 2));
    }
}

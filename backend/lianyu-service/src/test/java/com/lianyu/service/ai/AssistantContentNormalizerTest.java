package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class AssistantContentNormalizerTest {

    private static final char WJ = '\u2060';

    @Test
    void flattensNewlinesInsideParentheses() {
        String raw = "（被他轻轻摸头的瞬间，我愣了一下\n\n目光跟着他往背后瞟了一眼）我假装没看到。";
        String normalized = AssistantContentNormalizer.normalize(raw);
        assertEquals(
                "（被他轻轻摸头的瞬间，我愣了一下 目光跟着他往背后瞟了一眼" + WJ + "）我假装没看到。",
                normalized);
    }

    @Test
    void closesUnclosedParenthesisForInnerThoughtDisplay() {
        String raw = "（被他轻轻摸头的瞬间，我愣了一下";
        assertEquals("（被他轻轻摸头的瞬间，我愣了一下" + WJ + "）", AssistantContentNormalizer.normalize(raw));
    }

    @Test
    void stripsLeadingOrphanClose() {
        assertEquals("嗯——蛋糕？", AssistantContentNormalizer.normalize("）嗯——蛋糕？"));
    }

    @Test
    void collapsesHardNewlinesAndKeepsClosingParenAttached() {
        String raw = "嗯。(她把手机屏幕朝下扣在枕边，闭上眼睛。\n心里默默算着：还有八个多小时就能见到你了。\n这次是真的不回了。\n)";
        String normalized = AssistantContentNormalizer.normalize(raw);
        assertFalse(normalized.contains("\n"));
        assertEquals(
                "嗯。(她把手机屏幕朝下扣在枕边，闭上眼睛。 心里默默算着：还有八个多小时就能见到你了。 这次是真的不回了。"
                        + WJ
                        + ")",
                normalized);
    }

    @Test
    void prepareForSplitKeepsOutsideNewlinesForMultiBubble() {
        String raw = "先这样吧\n\n（其实还想多说两句）";
        String prepared = AssistantContentNormalizer.prepareForSplit(raw);
        assertEquals("先这样吧\n\n（其实还想多说两句）", prepared);
    }
}

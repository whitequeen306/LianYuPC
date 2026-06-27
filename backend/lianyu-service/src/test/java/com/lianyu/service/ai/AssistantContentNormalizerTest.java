package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AssistantContentNormalizerTest {

    @Test
    void flattensNewlinesInsideParentheses() {
        String raw = "（被他轻轻摸头的瞬间，我愣了一下\n\n目光跟着他往背后瞟了一眼）我假装没看到。";
        String normalized = AssistantContentNormalizer.normalize(raw);
        assertEquals("（被他轻轻摸头的瞬间，我愣了一下 目光跟着他往背后瞟了一眼）我假装没看到。", normalized);
    }

    @Test
    void closesUnclosedParenthesisForInnerThoughtDisplay() {
        String raw = "（被他轻轻摸头的瞬间，我愣了一下";
        assertEquals("（被他轻轻摸头的瞬间，我愣了一下）", AssistantContentNormalizer.normalize(raw));
    }

    @Test
    void stripsLeadingOrphanClose() {
        assertEquals("嗯——蛋糕？", AssistantContentNormalizer.normalize("）嗯——蛋糕？"));
    }
}

package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InnerThoughtFilterTest {

    @Test
    void strip_removesFullWidthParentheses() {
        assertEquals("好啊", InnerThoughtFilter.strip("好啊（心里有点堵）"));
    }

    @Test
    void strip_allInnerThought_returnsEmpty() {
        assertTrue(InnerThoughtFilter.isEmptyAfterStrip("（其实挺在乎的）"));
    }

    @Test
    void strip_removesHalfWidthParentheses() {
        assertEquals("Hi", InnerThoughtFilter.strip("Hi (I care)"));
    }

    @Test
    void stripIfDisabled_keepsTextWhenEnabled() {
        assertEquals("好啊（心里有点堵）", InnerThoughtFilter.stripIfDisabled("好啊（心里有点堵）", true));
    }

    @Test
    void stripIfDisabled_stripsWhenDisabled() {
        assertEquals("好啊", InnerThoughtFilter.stripIfDisabled("好啊（心里有点堵）", false));
    }

    @Test
    void strip_removesParentheticalClauseInSpeech() {
        assertEquals("你说是什么意思", InnerThoughtFilter.strip("你说（昨天那件事）是什么意思"));
    }
}

package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantReplySplitterTest {

    private final AssistantReplySplitter splitter = new AssistantReplySplitter();

    @Test
    void doesNotSplitNewlinesInsideParentheses() {
        String text = "（她靠近你，轻轻握住你的手\n\n）好的，我等一下倒没关系。";
        assertEquals(List.of(text), splitter.split(text, 3));
    }

    @Test
    void mergesPiecesWhenParenthesisSpansLines() {
        String text = String.join("\n",
                "（被他轻轻摸头的瞬间，我愣了一下，目光跟着他往背后瞟了一眼",
                "我假装没看到，收回视线抿了抿嘴",
                "）嗯——我猜你今天迟到的理由是蛋糕？");
        assertEquals(1, splitter.split(text, 3).size());
    }

    @Test
    void doesNotSplitOnSentencePunctuationInsideParentheses() {
        String text = "（被他轻轻摸头的瞬间，我愣了一下。logo落进眼里。）我假装没看到。";
        assertEquals(List.of(text), splitter.split(text, 3));
    }
}

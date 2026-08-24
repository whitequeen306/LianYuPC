package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void splitsComputerTaskFailureEvenWhenMaxRepliesIsOne() {
        String text = "好，您说可以了，那我我现在就试。麻烦稍等一下，我这边盯着呢。"
                + "这次还是没成，执行的时候报了个错，看来这台电脑这边的助手还没完全恢复。"
                + "真是让您费心了，试了这么多次都没帮上忙。";
        List<String> pieces = splitter.split(text, 1);
        assertEquals(2, pieces.size());
        assertEquals("好，您说可以了，那我我现在就试。麻烦稍等一下，我这边盯着呢。", pieces.get(0));
        assertTrue(pieces.get(1).startsWith("这次还是没成"));
    }

    @Test
    void doesNotForceSplitOnOrdinaryChat() {
        String text = "钟离先生吗……当然认识。往生堂的客卿，学识渊博。";
        List<String> pieces = splitter.split(text, 1);
        assertEquals(1, pieces.size());
    }
}

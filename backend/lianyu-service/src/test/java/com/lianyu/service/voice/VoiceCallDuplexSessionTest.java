package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VoiceCallDuplexSessionTest {

    @Test
    void markerIsHighestPriorityCutPoint() {
        // 模型显式停顿点：无视句法规则，立即成为合成边界
        assertEquals(6, VoiceCallDuplexSession.nextCommitEnd("要不要我陪你<|pause|>还是先休息", true));
        assertEquals(1, VoiceCallDuplexSession.nextCommitEnd("嗯<|pause|>我在听", false));
    }

    @Test
    void firstCommitMergesTinyLeadingSentence() {
        // 首段："好。"太短不单独成段，并到后文像样的边界，一次合成更连贯
        assertEquals(9, VoiceCallDuplexSession.nextCommitEnd("好。今晚想吃什么？", false));
        assertEquals(5, VoiceCallDuplexSession.nextCommitEnd("我在听呢。后面还有", false));
        assertEquals(-1, VoiceCallDuplexSession.nextCommitEnd("好。今", false));
    }

    @Test
    void firstCommitHardCutKeepsLatency() {
        assertEquals(12, VoiceCallDuplexSession.nextCommitEnd("哈哈哈哈哈哈哈哈哈哈哈哈哈哈哈哈", false));
    }

    @Test
    void laterCommitsCutAtSentenceEndOnly() {
        assertEquals(2, VoiceCallDuplexSession.nextCommitEnd("嗯。走", true));
        assertEquals(-1, VoiceCallDuplexSession.nextCommitEnd("今天天气不错", true));
    }

    @Test
    void laterCommitsFallbackOnLongBuffer() {
        assertEquals(11, VoiceCallDuplexSession.nextCommitEnd("aaaaaaaaaa，bbbbbbbbbbbbbbbbbbb", true));
        assertEquals(24, VoiceCallDuplexSession.nextCommitEnd("a".repeat(30), true));
    }

    @Test
    void incompleteMarkerPrefixIsNeverSplit() {
        assertEquals(9, VoiceCallDuplexSession.nextCommitEnd("今天天气不错要不要<|", false));
        assertEquals(-1, VoiceCallDuplexSession.nextCommitEnd("<|", false));
    }

    @Test
    void stripMarkersForPersistAndFlush() {
        assertEquals("好。走吧", VoiceCallDuplexSession.stripPauseMarkers("好。<|pause|>走吧"));
        assertEquals("走吧", VoiceCallDuplexSession.stripPauseMarkers("走吧<|"));
        assertEquals("", VoiceCallDuplexSession.stripPauseMarkers(null));
    }
}

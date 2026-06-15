package com.lianyu.service.moments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MomentsCommentOrchestratorTest {

    @Test
    void looksLikeThirdPersonAddress_detectsVocativePatterns() {
        assertTrue(MomentsCommentOrchestrator.looksLikeThirdPersonAddress(
                "五河琴里", "晚霞不错吧。不过你那叠文件真签完了？别是拿来当偷懒的挡箭牌啊，琴里。"));
        assertTrue(MomentsCommentOrchestrator.looksLikeThirdPersonAddress("琴里", "琴里，别偷懒"));
        assertFalse(MomentsCommentOrchestrator.looksLikeThirdPersonAddress(
                "五河琴里", "晚霞真好看，今天忙了一天。"));
    }
}

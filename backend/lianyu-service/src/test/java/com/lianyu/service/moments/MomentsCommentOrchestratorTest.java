package com.lianyu.service.moments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.dao.entity.MomentsInteractionState;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Test
    void isAbandoned_readsMetaFlag() {
        MomentsInteractionState state = new MomentsInteractionState();
        assertFalse(MomentsCommentOrchestrator.isAbandoned(state));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("abandoned", true);
        meta.put("abandonReason", "NO_USER_TEXT_MODEL");
        state.setLastPeerSampleJson(meta);
        assertTrue(MomentsCommentOrchestrator.isAbandoned(state));
    }

    @Test
    void readRetryCount_defaultsAndParses() {
        assertEquals(0, MomentsCommentOrchestrator.readRetryCount(null));

        MomentsInteractionState state = new MomentsInteractionState();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("retryCount", 2);
        state.setLastPeerSampleJson(meta);
        assertEquals(2, MomentsCommentOrchestrator.readRetryCount(state));

        meta.put("retryCount", "3");
        assertEquals(3, MomentsCommentOrchestrator.readRetryCount(state));
    }
}

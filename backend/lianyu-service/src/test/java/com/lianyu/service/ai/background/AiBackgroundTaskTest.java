package com.lianyu.service.ai.background;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class AiBackgroundTaskTest {

    @Test
    void factoryMethods_setExpectedFields() {
        AiBackgroundTask peer = AiBackgroundTask.momentsPeerComment(10L, 20L, 1, 0, List.of(20L, 21L));
        assertEquals(AiBackgroundJobType.MOMENTS_PEER_COMMENT, peer.type());
        assertEquals(10L, peer.postId());
        assertEquals(20L, peer.peerCharacterId());
        assertEquals(1, peer.peerIndex());
        assertEquals(0, peer.successCount());
        assertEquals(List.of(20L, 21L), peer.peerIds());

        AiBackgroundTask author = AiBackgroundTask.momentsAuthorReply(10L, 99L, 1);
        assertEquals(AiBackgroundJobType.MOMENTS_AUTHOR_REPLY, author.type());
        assertEquals(99L, author.commentId());
        assertEquals(1, author.attempt());

        AiBackgroundTask post = AiBackgroundTask.momentsPost(1L, 2L, 3L);
        assertEquals(AiBackgroundJobType.MOMENTS_POST, post.type());
        assertEquals(1L, post.userId());
        assertEquals(2L, post.conversationId());
        assertEquals(3L, post.characterId());

        AiBackgroundTask diary = AiBackgroundTask.characterDiary(1L, 3L);
        assertEquals(AiBackgroundJobType.CHARACTER_DIARY, diary.type());

        AiBackgroundTask cold = AiBackgroundTask.coldOpenFollowUp(1L, 2L);
        assertEquals(AiBackgroundJobType.COLD_OPEN_FOLLOWUP, cold.type());
        assertNull(cold.postId());

        AiBackgroundTask city = AiBackgroundTask.cityChangeFollowUp(1L, "上海", "北京");
        assertEquals("上海", city.previousCity());
        assertEquals("北京", city.newCity());

        AiBackgroundTask voice = AiBackgroundTask.voiceCallSummary(1L, 2L, 5L, "用户：你好");
        assertEquals(AiBackgroundJobType.VOICE_CALL_SUMMARY, voice.type());
        assertEquals(5L, voice.messageId());
        assertEquals("用户：你好", voice.transcript());
    }
}

package com.lianyu.service.ai.background;

import java.io.Serializable;
import java.util.List;

/**
 * 后台 AI 任务信封。字段按 type 选用，未用字段可为 null。
 */
public record AiBackgroundTask(
        AiBackgroundJobType type,
        Long userId,
        Long conversationId,
        Long characterId,
        Long postId,
        Long commentId,
        Long messageId,
        Long peerCharacterId,
        Integer peerIndex,
        Integer successCount,
        Integer attempt,
        List<Long> peerIds,
        String previousCity,
        String newCity,
        String transcript
) implements Serializable {

    public static AiBackgroundTask momentsPeerComment(Long postId,
                                                      Long peerCharacterId,
                                                      int peerIndex,
                                                      int successCount,
                                                      List<Long> peerIds) {
        return new AiBackgroundTask(
                AiBackgroundJobType.MOMENTS_PEER_COMMENT,
                null, null, null, postId, null, null, peerCharacterId,
                peerIndex, successCount, null, peerIds, null, null, null);
    }

    public static AiBackgroundTask momentsAuthorReply(Long postId, Long triggerCommentId, int attempt) {
        return new AiBackgroundTask(
                AiBackgroundJobType.MOMENTS_AUTHOR_REPLY,
                null, null, null, postId, triggerCommentId, null, null,
                null, null, attempt, null, null, null, null);
    }

    public static AiBackgroundTask momentsPost(Long userId, Long conversationId, Long characterId) {
        return new AiBackgroundTask(
                AiBackgroundJobType.MOMENTS_POST,
                userId, conversationId, characterId, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public static AiBackgroundTask characterDiary(Long userId, Long characterId) {
        return new AiBackgroundTask(
                AiBackgroundJobType.CHARACTER_DIARY,
                userId, null, characterId, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public static AiBackgroundTask coldOpenFollowUp(Long userId, Long conversationId) {
        return new AiBackgroundTask(
                AiBackgroundJobType.COLD_OPEN_FOLLOWUP,
                userId, conversationId, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public static AiBackgroundTask cityChangeFollowUp(Long userId, String previousCity, String newCity) {
        return new AiBackgroundTask(
                AiBackgroundJobType.CITY_CHANGE_FOLLOWUP,
                userId, null, null, null, null, null, null,
                null, null, null, null, previousCity, newCity, null);
    }

    public static AiBackgroundTask voiceCallSummary(Long userId, Long conversationId, Long messageId, String transcript) {
        return new AiBackgroundTask(
                AiBackgroundJobType.VOICE_CALL_SUMMARY,
                userId, conversationId, null, null, null, messageId, null,
                null, null, null, null, null, null, transcript);
    }
}

package com.lianyu.service.conversation;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 定时主动聊天：连续主动 N 次用户未回复则暂停，用户回复后清零。
 * 满 2 轮未回复时，可触发一次固定「催回复」语音，并暂停后续主动。
 */
@Component
@RequiredArgsConstructor
public class ProactiveUnrepliedThrottle {

    static final String KEY_PREFIX = "chat:proactive:unreplied:";
    static final String WAIT_VOICE_PREFIX = "chat:proactive:wait-voice:";
    /** Pause further AI proactives after this many unreplied sends. */
    static final int MAX_UNREPLIED = 5;
    /** After this many unreplied proactives, send fixed wait voice once. */
    static final int WAIT_VOICE_AFTER = 2;

    private final StringRedisTemplate redisTemplate;

    public boolean isPaused(Long conversationId) {
        return getCount(conversationId) >= MAX_UNREPLIED;
    }

    public int getCount(Long conversationId) {
        if (conversationId == null) {
            return 0;
        }
        String raw = redisTemplate.opsForValue().get(key(conversationId));
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            redisTemplate.delete(key(conversationId));
            return 0;
        }
    }

    /**
     * True when two AI proactives went unanswered and wait voice has not been sent this streak.
     */
    public boolean shouldSendWaitVoice(Long conversationId) {
        if (conversationId == null) {
            return false;
        }
        if (getCount(conversationId) < WAIT_VOICE_AFTER) {
            return false;
        }
        return !Boolean.TRUE.equals(redisTemplate.hasKey(waitKey(conversationId)));
    }

    public void markWaitVoiceSent(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        redisTemplate.opsForValue().set(waitKey(conversationId), "1", Duration.ofDays(7));
        // Pause further AI / timed proactives until the user replies.
        redisTemplate.opsForValue().set(key(conversationId), String.valueOf(MAX_UNREPLIED), Duration.ofDays(7));
    }

    public void recordProactiveSent(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        Long next = redisTemplate.opsForValue().increment(key(conversationId));
        if (next != null && next == 1L) {
            redisTemplate.expire(key(conversationId), Duration.ofDays(7));
        }
    }

    public void resetOnUserReply(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        redisTemplate.delete(key(conversationId));
        redisTemplate.delete(waitKey(conversationId));
    }

    private static String key(Long conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private static String waitKey(Long conversationId) {
        return WAIT_VOICE_PREFIX + conversationId;
    }
}

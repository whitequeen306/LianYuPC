package com.lianyu.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ProactiveUnrepliedThrottleTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ProactiveUnrepliedThrottle throttle;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        throttle = new ProactiveUnrepliedThrottle(redis);
    }

    @Test
    void waitVoiceAfterTwoUnreplied() {
        when(values.get("chat:proactive:unreplied:9")).thenReturn("2");
        when(redis.hasKey("chat:proactive:wait-voice:9")).thenReturn(false);
        assertThat(throttle.shouldSendWaitVoice(9L)).isTrue();

        when(values.get("chat:proactive:unreplied:9")).thenReturn("1");
        assertThat(throttle.shouldSendWaitVoice(9L)).isFalse();
    }

    @Test
    void markWaitVoicePausesUntilReply() {
        throttle.markWaitVoiceSent(9L);
        verify(values).set(eq("chat:proactive:wait-voice:9"), eq("1"), any(Duration.class));
        verify(values).set(eq("chat:proactive:unreplied:9"), eq("5"), any(Duration.class));
        when(values.get("chat:proactive:unreplied:9")).thenReturn("5");
        assertThat(throttle.isPaused(9L)).isTrue();
    }

    @Test
    void resetClearsWaitFlag() {
        throttle.resetOnUserReply(9L);
        verify(redis).delete("chat:proactive:unreplied:9");
        verify(redis).delete("chat:proactive:wait-voice:9");
    }
}

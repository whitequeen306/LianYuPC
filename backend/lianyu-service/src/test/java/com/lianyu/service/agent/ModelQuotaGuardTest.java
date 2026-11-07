package com.lianyu.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 额度熔断的三条硬要求：欠费要停、恢复要快、Redis 挂了不能把聊天堵死。
 */
class ModelQuotaGuardTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ModelQuotaGuard guard;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        guard = new ModelQuotaGuard(redisTemplate, new ObjectMapper());
    }

    @Test
    void allowsCallsWhenNoKeyIsPresent() {
        when(redisTemplate.hasKey("ai:quota:block:7")).thenReturn(false);
        assertTrue(guard.allow(7L));
    }

    @Test
    void blocksAfterInsufficientBalanceAndWritesFirstBackoff() {
        when(redisTemplate.hasKey("ai:quota:block:7")).thenReturn(true);
        when(valueOps.get("ai:quota:block:7")).thenReturn(null);

        long seconds = guard.recordFailure(7L, "provider_insufficient_balance", "model gateway http 402");

        assertEquals(600L, seconds, "首次熔断 10 分钟：够用户去充值，又不会把主动关怀停太久");
        assertFalse(guard.allow(7L));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("ai:quota:block:7"), payload.capture(), eq(Duration.ofSeconds(600)));
        assertTrue(payload.getValue().contains("provider_insufficient_balance"));
        assertTrue(payload.getValue().contains("\"fails\":1"));
    }

    @Test
    void backoffGrowsWithRepeatedFailuresAndCapsAtADay() {
        assertEquals(600L, ModelQuotaGuard.backoffSeconds(1));
        assertEquals(1800L, ModelQuotaGuard.backoffSeconds(2));
        assertEquals(7200L, ModelQuotaGuard.backoffSeconds(3));
        assertEquals(21600L, ModelQuotaGuard.backoffSeconds(4));
        assertEquals(86400L, ModelQuotaGuard.backoffSeconds(5));
        assertEquals(86400L, ModelQuotaGuard.backoffSeconds(99), "封顶 24 小时，不能无限翻倍");
    }

    @Test
    void secondFailureReadsPreviousCounterAndLengthensBackoff() {
        when(valueOps.get("ai:quota:block:7")).thenReturn("{\"state\":\"blocked\",\"fails\":2}");

        long seconds = guard.recordFailure(7L, "provider_insufficient_balance", "");

        assertEquals(7200L, seconds);
        verify(valueOps).set(eq("ai:quota:block:7"), anyString(), eq(Duration.ofSeconds(7200)));
    }

    @Test
    void ignoresNonQuotaErrorsSoTransientFailuresNeverOpenTheCircuit() {
        assertEquals(0L, guard.recordFailure(7L, "upstream_timeout", ""));
        assertEquals(0L, guard.recordFailure(7L, "", ""));
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void modelNotFoundAlsoBlocksBecauseItWouldFailOnEveryScan() {
        assertTrue(ModelQuotaGuard.isBlockingCode("provider_model_not_found"));
        assertTrue(ModelQuotaGuard.isBlockingCode("provider_invalid_api_key"));
        assertFalse(ModelQuotaGuard.isBlockingCode("upstream_timeout"));
    }

    @Test
    void successClearsTheBlockSoProactiveResumesImmediately() {
        when(redisTemplate.hasKey("ai:quota:block:7")).thenReturn(true);

        guard.recordSuccess(7L);

        verify(redisTemplate).delete("ai:quota:block:7");
    }

    @Test
    void successOnANonBlockedUserDoesNotTouchRedis() {
        when(redisTemplate.hasKey("ai:quota:block:7")).thenReturn(false);

        guard.recordSuccess(7L);

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void snapshotReportsRetryWindowForTheClientBanner() {
        long until = System.currentTimeMillis() + 300_000L;
        when(valueOps.get("ai:quota:block:7")).thenReturn(
                "{\"state\":\"blocked\",\"code\":\"provider_insufficient_balance\",\"fails\":2,"
                        + "\"blockedUntil\":" + until + "}");

        Map<String, Object> snapshot = guard.snapshot(7L);

        assertEquals("blocked", snapshot.get("state"));
        assertEquals("provider_insufficient_balance", snapshot.get("code"));
        assertEquals(false, snapshot.get("proactiveEnabled"));
        long retry = ((Number) snapshot.get("retryAfterSeconds")).longValue();
        assertTrue(retry > 250 && retry <= 300, "剩余秒数要能让前端显示倒计时，实际=" + retry);
    }

    @Test
    void snapshotOfHealthyUserSaysProactiveIsOn() {
        when(valueOps.get("ai:quota:block:7")).thenReturn(null);

        Map<String, Object> snapshot = guard.snapshot(7L);

        assertEquals("ok", snapshot.get("state"));
        assertEquals(true, snapshot.get("proactiveEnabled"));
    }

    @Test
    void redisOutageMustNotBlockTheCall() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertTrue(guard.allow(7L), "监控组件挂掉不能让用户聊不了天");
    }
}

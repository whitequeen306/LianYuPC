package com.lianyu.service.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.common.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new AuthRateLimiter(redisTemplate);
        ReflectionTestUtils.setField(limiter, "perIpPerMinute", 2);
        ReflectionTestUtils.setField(limiter, "perUsernamePerMinute", 2);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void checkLoginOrRegister_allowsUnderLimit() {
        when(valueOperations.increment(anyString())).thenReturn(1L, 1L);
        assertDoesNotThrow(() -> limiter.checkLoginOrRegister("1.2.3.4", "alice"));
    }

    @Test
    void checkLoginOrRegister_throwsWhenIpExceeded() {
        when(valueOperations.increment(anyString())).thenReturn(3L);
        assertThrows(BusinessException.class,
                () -> limiter.checkLoginOrRegister("1.2.3.4", null));
    }

    @Test
    void checkRateLimit_setsTtlOnFirstHit() {
        when(valueOperations.increment("rate:observe:ip:1.2.3.4")).thenReturn(1L);
        limiter.checkRateLimit("rate:observe:ip:", "1.2.3.4", 30, Duration.ofHours(1), "too many");
        verifyExpireSet();
    }

    private void verifyExpireSet() {
        org.mockito.Mockito.verify(redisTemplate).expire(anyString(), any(Duration.class));
    }
}

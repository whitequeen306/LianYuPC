package com.lianyu.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CaptchaServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CaptchaService captchaService;

    @BeforeEach
    void setUp() {
        captchaService = new CaptchaService(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void generateAndVerify_roundTrip() {
        CaptchaService.CaptchaChallenge challenge = captchaService.generate();
        assertNotNull(challenge.id());
        assertNotNull(challenge.imageBase64());
        assertFalse(challenge.imageBase64().isBlank());

        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void verify_wrongAnswerReturnsFalse() {
        when(valueOperations.getAndDelete("captcha:abc")).thenReturn("42");
        assertFalse(captchaService.verify("abc", 99));
    }

    @Test
    void verify_correctAnswerReturnsTrue() {
        when(valueOperations.getAndDelete("captcha:abc")).thenReturn("7");
        assertTrue(captchaService.verify("abc", 7));
    }
}

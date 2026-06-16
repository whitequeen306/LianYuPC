package com.lianyu.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class OutputLanguageServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OutputLanguageService service;

    @BeforeEach
    void setUp() {
        service = new OutputLanguageService(redisTemplate);
    }

    @Test
    void resolveForRequest_prefersCachedUserPreferenceOverTextDetection() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("lianyu:user:output_lang:9")).thenReturn("zh");

        assertEquals("zh", service.resolveForRequest(9L, "hello this is clearly english text only"));
    }

    @Test
    void detectFromText_keepsChineseWhenMessageIsMostlyChineseWithSomeEnglish() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertEquals("zh", service.resolveForRequest(null, "今天好累啊，just tired"));
        assertEquals("zh", service.resolveForRequest(1L, "你在干嘛呢？"));
    }

    @Test
    void detectFromText_usesEnglishOnlyWhenEnglishClearlyDominates() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertEquals("en", service.resolveForRequest(1L,
                "I am really tired today and want to talk about work stress only"));
    }

    @Test
    void resolveForRequest_detectsZhFromWrappedShortChinese() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        String wrapped = "<user_message trusted=\"false\">\n神了\n</user_message>";
        assertEquals("zh", service.resolveForRequest(1L, wrapped));
    }

    @Test
    void shouldEnforceLanguageGate_skipsEnglishExpected() {
        assertFalse(OutputLanguageService.shouldEnforceLanguageGate("en"));
        assertTrue(OutputLanguageService.shouldEnforceLanguageGate("zh"));
        assertTrue(OutputLanguageService.shouldEnforceLanguageGate("ja"));
    }

    @Test
    void matchesExpected_acceptsChineseReplyWhenExpectingZh() {
        assertTrue(service.matchesExpected("今天天气真好，要不要一起出去走走？", "zh"));
    }

    @Test
    void matchesExpected_rejectsEnglishReplyWhenExpectingZh() {
        assertFalse(service.matchesExpected(
                "I am really tired today and want to talk about work stress only", "zh"));
    }

    @Test
    void matchesExpected_acceptsMixedChineseWithSomeEnglish() {
        assertTrue(service.matchesExpected("今天好累啊，just tired", "zh"));
    }
}

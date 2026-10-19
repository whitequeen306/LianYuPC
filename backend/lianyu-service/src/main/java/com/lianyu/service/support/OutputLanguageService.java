package com.lianyu.service.support;

import com.lianyu.common.i18n.OutputLanguage;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutputLanguageService {

    private static final String REDIS_KEY_PREFIX = "lianyu:user:output_lang:";
    private static final Duration CACHE_TTL = Duration.ofDays(90);

    private final StringRedisTemplate redisTemplate;

    public void cacheForUser(Long userId, String languageCode) {
        if (userId == null || languageCode == null || languageCode.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(
                redisKey(userId),
                OutputLanguage.fromCode(languageCode).getCode(),
                CACHE_TTL
        );
    }

    public String resolveForUser(Long userId) {
        if (userId != null) {
            String cached = redisTemplate.opsForValue().get(redisKey(userId));
            if (cached != null && !cached.isBlank()) {
                return OutputLanguage.fromCode(cached).getCode();
            }
        }
        return OutputLanguage.ZH.getCode();
    }

    /**
     * 解析本条请求的模型回复语言：优先用户设置（Redis 缓存），其次从文本推断，最后默认中文。
     */
    public String resolveForRequest(Long userId, String sampleText) {
        if (hasCachedPreference(userId)) {
            return resolveForUser(userId);
        }
        if (sampleText != null && !sampleText.isBlank()) {
            return detectFromText(sampleText).getCode();
        }
        return resolveForUser(userId);
    }

    private boolean hasCachedPreference(Long userId) {
        if (userId == null) {
            return false;
        }
        String cached = redisTemplate.opsForValue().get(redisKey(userId));
        return cached != null && !cached.isBlank();
    }

    /**
     * 是否应对本条回复启用语言门控（仅拦截非期望语言，永不强制改成英文）。
     */
    public static boolean shouldEnforceLanguageGate(String expectedLangCode) {
        if (expectedLangCode == null || expectedLangCode.isBlank()) {
            return false;
        }
        return OutputLanguage.fromCode(expectedLangCode) != OutputLanguage.EN;
    }

    /**
     * 判断模型输出是否符合期望语言（仅分类，不修改文本）。
     */
    public boolean matchesExpected(String content, String expectedLangCode) {
        if (content == null || content.isBlank()) {
            return true;
        }
        if (expectedLangCode == null || expectedLangCode.isBlank()) {
            return true;
        }
        OutputLanguage expected = OutputLanguage.fromCode(expectedLangCode);
        if (expected == OutputLanguage.ZH || expected == OutputLanguage.ZH_TW) {
            if (countIdeographic(stripMarkupForDetection(content)) > 0) {
                return true;
            }
        }
        OutputLanguage detected = detectFromText(content);
        return languageMatches(expected, detected);
    }

    private static boolean languageMatches(OutputLanguage expected, OutputLanguage detected) {
        return switch (expected) {
            case ZH, ZH_TW -> detected == OutputLanguage.ZH || detected == OutputLanguage.ZH_TW;
            case JA -> detected == OutputLanguage.JA;
            case EN -> detected == OutputLanguage.EN;
        };
    }

    private static OutputLanguage detectFromText(String text) {
        String sample = stripMarkupForDetection(text);
        int ja = 0;
        int en = 0;
        int zh = 0;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)) {
                ja++;
            } else if (c < 128 && Character.isLetter(c)) {
                en++;
            } else if (Character.isIdeographic(c)) {
                zh++;
            }
        }
        if (ja > en && ja >= zh && ja >= 3) {
            return OutputLanguage.JA;
        }
        // 中英混杂时优先中文，避免少量英文词/缩写把整轮回复带成英文
        if (en > zh * 2 && en > ja && en >= 5) {
            return OutputLanguage.EN;
        }
        if (zh > 0) {
            return OutputLanguage.ZH;
        }
        if (ja >= en && ja > 0) {
            return OutputLanguage.JA;
        }
        if (en > 0) {
            return OutputLanguage.EN;
        }
        return OutputLanguage.ZH;
    }

    private static String stripMarkupForDetection(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = text.replaceAll("<[^>]*>", " ");
        stripped = stripped.replaceAll("&[a-z]+;", " ");
        stripped = stripped.replaceAll("&#\\d+;", " ");
        return stripped.trim();
    }

    private static int countIdeographic(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isIdeographic(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static String redisKey(Long userId) {
        return REDIS_KEY_PREFIX + userId;
    }
}

package com.lianyu.service;

import com.lianyu.common.i18n.OutputLanguage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

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
     * 按本条用户输入推断模型回复语言；无文本时回退用户缓存偏好。
     */
    public String resolveForRequest(Long userId, String sampleText) {
        if (sampleText != null && !sampleText.isBlank()) {
            return detectFromText(sampleText).getCode();
        }
        return resolveForUser(userId);
    }

    private static OutputLanguage detectFromText(String text) {
        int ja = 0;
        int en = 0;
        int zh = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)) {
                ja++;
            } else if (c < 128 && Character.isLetter(c)) {
                en++;
            } else if (Character.isIdeographic(c)) {
                zh++;
            }
        }
        if (ja > en && ja >= zh) {
            return OutputLanguage.JA;
        }
        if (en > zh && en > ja) {
            return OutputLanguage.EN;
        }
        return OutputLanguage.ZH;
    }

    public String buildOutputLanguageBlock(String languageCode) {
        OutputLanguage lang = OutputLanguage.fromCode(languageCode);
        return switch (lang) {
            case JA -> """
                    【返答言語】
                    ユーザーへの返答は必ず日本語で行うこと。他言語は使わない。""";
            case EN -> """
                    【Reply language】
                    You must reply to the user in English only. Do not use other languages.""";
            case ZH_TW -> """
                    【回覆語言】
                    你必須使用繁體中文回覆用戶，不要使用其他語言。""";
            default -> """
                    【回复语言】
                    你必须使用简体中文回复用户，不要使用其他语言。""";
        };
    }

    public String buildNaturalStyleBlock(String languageCode) {
        OutputLanguage lang = OutputLanguage.fromCode(languageCode);
        return switch (lang) {
            case JA -> "\n\n表現要件：舞台説明や動作の括弧描写（例「（微笑）」）は出力しない。自然なチャット文のみ。";
            case EN -> "\n\nStyle: Do not output stage directions or action descriptions in parentheses. Natural chat text only.";
            case ZH_TW -> "\n\n表達要求：不要輸出任何舞台說明/動作括號描寫（如「（微笑）」「(嘆氣)」）。只輸出自然聊天文本。";
            default -> "\n\n表达要求：不要输出任何舞台说明/动作括号描写（如“（微笑）”“(叹气)”）。只输出自然聊天文本。";
        };
    }

    private static String redisKey(Long userId) {
        return REDIS_KEY_PREFIX + userId;
    }
}

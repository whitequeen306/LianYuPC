package com.lianyu.security.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JasyptUtilLooksLikeApiKeyTest {

    @Test
    void acceptsOpenAiGoogleOllamaAndOpaqueTokens() {
        assertThat(JasyptUtil.looksLikeApiKey("sk-abc1234567890")).isTrue();
        assertThat(JasyptUtil.looksLikeApiKey("AIzaSyDummyGoogleGeminiKey123")).isTrue();
        assertThat(JasyptUtil.looksLikeApiKey("local")).isTrue();
        assertThat(JasyptUtil.looksLikeApiKey("clove-relay-token_v1.2")).isTrue();
    }

    @Test
    void rejectsBlankCiphertextAndJunk() {
        assertThat(JasyptUtil.looksLikeApiKey(null)).isFalse();
        assertThat(JasyptUtil.looksLikeApiKey("")).isFalse();
        assertThat(JasyptUtil.looksLikeApiKey("ab")).isFalse();
        assertThat(JasyptUtil.looksLikeApiKey("ENC(abc)")).isFalse();
        assertThat(JasyptUtil.looksLikeApiKey("sk-abc\ndef")).isFalse();
        assertThat(JasyptUtil.looksLikeApiKey("key with spaces")).isFalse();
    }
}

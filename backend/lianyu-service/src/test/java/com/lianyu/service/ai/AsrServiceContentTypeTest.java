package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AsrServiceContentTypeTest {

    @Test
    void normalizeContentType_stripsCodecsParameter() {
        assertThat(AsrService.normalizeContentType("audio/webm;codecs=opus"))
                .isEqualTo("audio/webm");
        assertThat(AsrService.normalizeContentType("audio/webm; codecs=opus"))
                .isEqualTo("audio/webm");
        assertThat(AsrService.normalizeContentType("audio/ogg;codecs=opus"))
                .isEqualTo("audio/ogg");
        assertThat(AsrService.normalizeContentType("audio/wav"))
                .isEqualTo("audio/wav");
        assertThat(AsrService.normalizeContentType(null)).isEmpty();
        assertThat(AsrService.normalizeContentType("  ")).isEmpty();
    }
}

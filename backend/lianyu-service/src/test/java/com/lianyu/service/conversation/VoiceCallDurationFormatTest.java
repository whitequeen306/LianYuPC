package com.lianyu.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VoiceCallDurationFormatTest {

    @Test
    void formatDurationZh_coversSecondsAndMinutes() {
        assertThat(VoiceCallService.formatDurationZh(0)).isEqualTo("0秒");
        assertThat(VoiceCallService.formatDurationZh(24)).isEqualTo("24秒");
        assertThat(VoiceCallService.formatDurationZh(60)).isEqualTo("1分钟");
        assertThat(VoiceCallService.formatDurationZh(204)).isEqualTo("3分24秒");
    }
}

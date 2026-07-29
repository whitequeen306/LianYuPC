package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class DashScopeTtsServiceNetworkTest {

    @Test
    void acceptsOnlyHttpsDashScopeOrOssAudioHosts() {
        assertThat(DashScopeTtsService.isAllowedTtsAudioHost(
                "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/audio.wav")).isTrue();
        assertThat(DashScopeTtsService.isAllowedTtsAudioHost(
                "https://dashscope.aliyuncs.com/audio.wav")).isTrue();
        assertThat(DashScopeTtsService.isAllowedTtsAudioHost(
                "http://dashscope.aliyuncs.com/audio.wav")).isFalse();
        assertThat(DashScopeTtsService.isAllowedTtsAudioHost(
                "https://evil.aliyuncs.com/audio.wav")).isFalse();
        assertThat(DashScopeTtsService.isAllowedTtsAudioHost(
                "https://dashscope.aliyuncs.com.evil.example/audio.wav")).isFalse();
    }

    @Test
    void classifiesDnsAndProviderTransientFailuresForRetry() {
        assertThat(DashScopeTtsService.isTransientNetworkFailure(
                new IOException("download failed", new UnknownHostException("oss host")))).isTrue();
        assertThat(DashScopeTtsService.isTransientNetworkFailure(
                new IOException("DashScope TTS transient HTTP 503"))).isTrue();
        assertThat(DashScopeTtsService.isTransientNetworkFailure(
                new IOException("HTTP 401"))).isFalse();
    }
}

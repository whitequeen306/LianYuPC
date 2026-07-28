package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AsrStreamClientUrlTest {

    @Test
    void convertsHttpBaseToWs() {
        assertThat(AsrStreamClient.toWsUrl("http://asr:8080")).isEqualTo("ws://asr:8080");
        assertThat(AsrStreamClient.toWsUrl("https://asr.example/")).isEqualTo("wss://asr.example");
        assertThat(AsrStreamClient.toWsUrl("ws://localhost:8081")).isEqualTo("ws://localhost:8081");
    }
}

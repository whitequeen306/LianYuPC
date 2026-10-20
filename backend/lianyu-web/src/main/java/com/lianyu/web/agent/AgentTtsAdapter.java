package com.lianyu.web.agent;

import com.lianyu.service.agent.TtsPort;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 非实时语音合成交给 Python。
 *
 * <p>失败一律返回 {@code null}（不抛）——「合成不出来」在业务上是可降级的，
 * 抛异常会把桌面问候 / 语音通话从「少一段音频」变成「整条链路失败」。
 */
@Slf4j
@Component
public class AgentTtsAdapter implements TtsPort {

    private final AgentRuntimeClient client;

    public AgentTtsAdapter(AgentRuntimeClient client) {
        this.client = client;
    }

    @Override
    public SynthesizedAudio synthesize(SynthesizeRequest request) {
        if (request == null || request.voiceId() == null || request.voiceId().isBlank()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // userId 固定 0：合成是纯能力调用，凭据就是参数本身，没有按用户区分的数据。
        // 仍要传是因为 Python 侧有 X-User-Id 与 body.userId 的一致性校验（缺省即跳过）。
        body.put("userId", 0L);
        body.put("apiKey", request.apiKey() == null ? "" : request.apiKey());
        body.put("baseUrl", request.baseUrl() == null ? "" : request.baseUrl());
        body.put("model", request.model() == null ? "" : request.model());
        body.put("voiceId", request.voiceId());
        body.put("text", request.text() == null ? "" : request.text());
        body.put("languageType", request.languageType() == null ? "Chinese" : request.languageType());

        AgentRuntimeClient.BinaryResponse response =
                client.postBytes("/agent/tts/synthesize", 0L, body);
        if (response == null || response.bytes() == null || response.bytes().length == 0) {
            log.warn("TTS agent returned no audio for label={}", request.logLabel());
            return null;
        }
        return SynthesizedAudio.of(response.contentType(), response.bytes());
    }
}

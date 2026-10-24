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
        AgentRuntimeClient.BinaryResponse response =
                client.postBytes("/agent/tts/synthesize", 0L, toBody(request));
        if (response == null || response.bytes() == null || response.bytes().length == 0) {
            log.warn("TTS agent returned no audio for label={}", request.logLabel());
            return null;
        }
        return SynthesizedAudio.of(response.contentType(), response.bytes());
    }

    /**
     * 流式合成：把 Python 逐块下发的 PCM 原样转给调用方。
     *
     * <p>块是裸字节（`X-Audio-Mime: audio/pcm`），**不做 base64、不做字符解码** ——
     * 这里多一次编解码就多一次出错机会，而且通话对延迟敏感。
     */
    @Override
    public void streamSynthesize(SynthesizeRequest request, TtsChunkSink sink) {
        if (request == null || sink == null
                || request.voiceId() == null || request.voiceId().isBlank()) {
            return;
        }
        client.postStreamBytes("/agent/tts/stream", 0L, toBody(request))
                .subscribe(
                        chunk -> {
                            try {
                                sink.onChunk(chunk);
                            } catch (Exception e) {
                                // 下游（WebSocket 写）失败不能把上游订阅带崩
                                log.warn("TTS chunk deliver failed label={}: {}", request.logLabel(), e.toString());
                            }
                        },
                        error -> {
                            log.warn("TTS stream failed label={}: {}", request.logLabel(), error.toString());
                            sink.onError(error.toString());
                        },
                        sink::onDone);
    }

    private static Map<String, Object> toBody(SynthesizeRequest request) {
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
        // 语速倍率：只有音色配了 rates 才带（雷电将军 1.15），其余走上游默认 1.0
        if (request.rate() != null && request.rate() > 0) {
            body.put("rate", request.rate());
        }
        return body;
    }
}

package com.lianyu.service.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.service.ai.AsrStreamClient;
import com.lianyu.service.agent.TtsPort;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.dto.AiChatRequest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Voice call duplex: PCM → Zipformer → LLM token stream → realtime VC TTS (HTTP fallback).
 *
 * <p>Realtime uses {@code realtimeVoices}; HTTP fallback uses {@code voices}. Never mix IDs.
 */
@Slf4j
public class VoiceCallDuplexSession {

    private final VoiceEventSink sink;
    private final ObjectMapper objectMapper;
    private final long userId;
    private final long conversationId;
    private final VoiceCallService voiceCallService;
    private final VoiceSynthesisService voiceSynthesisService;
    private final AsrStreamClient.Session asr;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean turnBusy = new AtomicBoolean(false);
    private final AtomicBoolean audioSent = new AtomicBoolean(false);
    private final AtomicReference<java.util.concurrent.CompletableFuture<?>> llmFuture = new AtomicReference<>();
    private final Object sentenceLock = new Object();
    /** LLM delta 展示缓冲：marker 不泄露到前端字幕。guarded by {@link #sentenceLock} */
    private final StringBuilder llmEmitBuf = new StringBuilder();
    private volatile String lastPartial = "";
    private volatile long turnStartedAtMs;
    private volatile long llmFirstDeltaAtMs;
    private volatile long firstAudioAtMs;
    private final VoiceCallTarget target;

    public VoiceCallDuplexSession(
            VoiceEventSink sink,
            ObjectMapper objectMapper,
            long userId,
            long conversationId,
            VoiceCallService voiceCallService,
            AsrStreamClient asrStreamClient,
            VoiceSynthesisService voiceSynthesisService) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.userId = userId;
        this.conversationId = conversationId;
        this.voiceCallService = voiceCallService;
        this.voiceSynthesisService = voiceSynthesisService;
        this.target = voiceCallService.resolveAndAssertCallTarget(userId, conversationId);
        this.asr = asrStreamClient.open(new AsrStreamClient.Listener() {
            @Override
            public void onPartial(String text) {
                lastPartial = text == null ? "" : text;
                emitJson("asr.partial", n -> n.put("text", lastPartial));
            }

            @Override
            public void onFinal(String text) {
                String t = text == null ? "" : text.trim();
                if (!t.isBlank()) {
                    lastPartial = t;
                    emitJson("asr.partial", n -> n.put("text", t));
                }
            }

            @Override
            public void onEndpoint() {
                triggerTurnFromAsr();
            }

            @Override
            public void onError(String message) {
                emitError("ASR_ERROR", "语音识别失败");
            }

            @Override
            public void onClosed() {
                // ignore
            }
        });
    }

    public void onPcm(byte[] pcm) {
        if (closed.get() || pcm == null || pcm.length == 0) {
            return;
        }
        asr.sendPcm(pcm);
    }

    public void bargeIn() {
        cancelInFlightTurn();
        emitJson("turn.cancelled", n -> {
        });
    }

    public void clientEndpoint() {
        triggerTurnFromAsr();
    }

    private void triggerTurnFromAsr() {
        String text;
        synchronized (this) {
            text = lastPartial;
            lastPartial = "";
        }
        try {
            asr.reset();
        } catch (Exception e) {
            log.debug("ASR reset after call endpoint failed: {}", e.toString());
        }
        startTurn(text);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelInFlightTurn();
        asr.close();
    }

    private void startTurn(String rawText) {
        if (closed.get()) {
            return;
        }
        String userText = UserInputSanitizer.sanitizeChatMessage(
                rawText == null ? "" : rawText).storedText();
        if (userText.isBlank()) {
            return;
        }
        if (!turnBusy.compareAndSet(false, true)) {
            return;
        }
        turnStartedAtMs = System.currentTimeMillis();
        llmFirstDeltaAtMs = 0L;
        firstAudioAtMs = 0L;
        emitJson("asr.final", n -> n.put("text", userText));
        emitJson("turn.start", n -> n.put("userText", userText));
        log.info("Voice duplex timing conv={} stage=turn_start userLen={}", conversationId, userText.length());

        Thread worker = new Thread(() -> {
            try {
                StringBuilder full = new StringBuilder();
                synchronized (sentenceLock) {
                    llmEmitBuf.setLength(0);
                }
                audioSent.set(false);

                long persistStart = System.currentTimeMillis();
                voiceCallService.persistUserTurn(userId, conversationId, userText);
                AiChatRequest aiRequest = voiceCallService.buildVoiceCallAiRequest(
                        userId, conversationId, userText);
                log.info("Voice duplex timing conv={} stage=persist_and_build ms={}",
                        conversationId, System.currentTimeMillis() - persistStart);

                var future = voiceCallService.streamVoiceReply(userId, aiRequest, delta -> {
                    if (delta == null || delta.isEmpty() || closed.get()) {
                        return;
                    }
                    markLlmFirstDelta();
                    full.append(delta);
                    String visible = sanitizeDeltaForEmit(delta);
                    if (!visible.isEmpty()) {
                        emitJson("llm.delta", n -> n.put("text", visible));
                    }
                });
                llmFuture.set(future);
                String rawReply = future.join();
                if (closed.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }

                String spoken = InnerThoughtFilter.strip(
                        stripPauseMarkers(rawReply == null ? "" : rawReply.trim()));
                if (spoken.isBlank()) {
                    spoken = "我在听，你再说一遍。";
                }
                spoken = voiceCallService.clampReply(spoken);
                voiceCallService.persistAssistantTurn(userId, conversationId, spoken);

                if (target.isLocal()) {
                    emitLocalTts(spoken);
                } else {
                    // 语音通话只有一条合成路径：按块流式（cosyvoice-v3-flash + 复刻音色）。
                    // 实时 WS 那条路已于 2026-10-22 整体删除（连同那个 DashScope 实时客户端类）：
                    // 平台不再提供「支持复刻音色的实时 TTS 模型」。
                    log.info("Voice duplex TTS streaming mode={} pet={} conv={} elapsedMs={}",
                            target.mode(), target.petId(), conversationId,
                            System.currentTimeMillis() - turnStartedAtMs);
                    streamHttpFallback(spoken);
                }

                String reply = spoken;
                long totalMs = System.currentTimeMillis() - turnStartedAtMs;
                log.info("Voice duplex timing conv={} stage=turn_done totalMs={} firstAudioMs={}",
                        conversationId, totalMs, firstAudioAtMs == 0L ? -1 : firstAudioAtMs - turnStartedAtMs);
                emitJson("turn.done", n -> {
                    n.put("userText", userText);
                    n.put("replyText", reply);
                });
            } catch (Exception e) {
                log.warn("Voice duplex turn failed: {}", e.toString());
                emitError("TURN_ERROR", "角色暂时无法回复，请稍后再试");
            } finally {
                llmFuture.set(null);
                turnBusy.set(false);
            }
        }, "voice-call-duplex");
        worker.setDaemon(true);
        worker.start();
    }

    public VoiceCallTarget getTarget() {
        return target;
    }

    private void emitLocalTts(String spoken) {
        if (spoken == null || spoken.isBlank()) {
            return;
        }
        markFirstAudio();
        audioSent.set(true);
        emitJson("tts.local", n -> {
            n.put("text", spoken);
            n.put("endpoint", target.endpoint() == null ? "" : target.endpoint());
            n.put("refAudioUrl", target.refAudioUrl() == null ? "" : target.refAudioUrl());
            n.put("refText", target.refText() == null ? "" : target.refText());
            n.put("provider", CustomVoiceProviders.GPTSOVITS_LOCAL);
        });
        emitJson("tts.done", n -> {
        });
    }

    /**
     * 非实时兜底的**流式**合成：按块下发，客户端边收边播。
     *
     * <p>这是「更流畅」的核心改动。原实现是整句合成完再一次 sendAudio，
     * 首帧要等整句音频落地；改为流式后第一块就能播（实测 841ms vs 1440ms），
     * 后面的块边播边收。客户端本来就支持多条 `tts.audio` 帧排队播放
     * （useVoiceDuplex.js → createPcmPlayback().enqueueBase64Audio），所以无需改前端。
     *
     * <p>用 CountDownLatch 等流结束：一是保持 `tts.done` 在 `turn.done` 之前的事件顺序
     * （否则前端会在音频还在播时就把 phase 复位），二是让 timing 日志能记到真实的
     * 首帧与总时长。等待有上限，上游卡死不会把工作线程永久占住。
     */
    private void streamHttpFallback(String spoken) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean firstChunk = new AtomicBoolean(false);

        voiceCallService.streamSynthesizeForTarget(target, spoken, new TtsPort.TtsChunkSink() {
            @Override
            public void onChunk(byte[] pcm) {
                if (pcm == null || pcm.length == 0) {
                    return;
                }
                if (firstChunk.compareAndSet(false, true)) {
                    markFirstAudio();
                    audioSent.set(true);
                }
                sendAudio(pcm, "audio/pcm");
            }

            @Override
            public void onDone() {
                done.countDown();
            }

            @Override
            public void onError(String message) {
                log.warn("Voice duplex stream TTS error conv={}: {}", conversationId, message);
                done.countDown();
            }
        });

        try {
            if (!done.await(45, TimeUnit.SECONDS)) {
                log.warn("Voice duplex stream TTS timed out conv={}", conversationId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!firstChunk.get()) {
            emitError("TTS_ERROR", "语音合成失败，请稍后再试");
            return;
        }
        emitJson("tts.done", n -> {
        });
    }

    /** 字幕展示用：剥掉完整 marker；尾部疑似未完整 marker 前缀的部分留到下一个 delta 再定。 */
    private String sanitizeDeltaForEmit(String delta) {
        synchronized (sentenceLock) {
            llmEmitBuf.append(delta);
            String s = llmEmitBuf.toString().replace(PAUSE_TOKEN, "");
            int idx = s.lastIndexOf("<|");
            if (idx >= 0 && s.length() - idx < PAUSE_TOKEN.length()) {
                String out = s.substring(0, idx);
                llmEmitBuf.setLength(0);
                llmEmitBuf.append(s.substring(idx));
                return out;
            }
            llmEmitBuf.setLength(0);
            return s;
        }
    }

    /** marker 及残片不进 TTS、不进聊天记录。 */
    static String stripPauseMarkers(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace(PAUSE_TOKEN, "").replace("<|", "");
    }

    /** 角色模型自主标记的停顿点：语音 prompt 指示其在需要换气/停顿处输出。 */
    static final String PAUSE_TOKEN = "<|pause|>";
    private static final String STRONG_ENDERS = "。！？!?\n";
    private static final String WEAK_PAUSES = "，,、；;";

    /**
     * 合成边界决策（完全状态化）：只认两种停顿 —— 模型显式 {@link #PAUSE_TOKEN} 和句末强标点。
     * 取消字数硬切 / 逗号兜底；长句（≥2 句）按句界切，短句（≤20 字）整句一次合成。
     * 未完整 marker 前缀永不被切开。
     *
     * <p>当前**暂未接入**：实时 WS 退役后，通话改走整段流式（{@code streamHttpFallback}）。
     * 保留它是因为下一步「按句流水线预取」（上一句在播、下一句已在合成）要用同一套切分规则，
     * 而且它带完整单测（{@code VoiceCallDuplexSessionTest}）——删掉再重写是浪费。
     */
    static int nextCommitEnd(String buf, boolean firstCommitSent) {
        if (buf == null || buf.isEmpty()) {
            return -1;
        }
        int marker = buf.indexOf(PAUSE_TOKEN);
        if (marker > 0) {
            return marker;
        }
        if (marker == 0) {
            return -1;
        }
        int partial = buf.indexOf("<|");
        if (partial > 0) {
            return partial;
        }
        if (partial == 0) {
            return -1;
        }
        int strong = indexOfAnyFrom(buf, STRONG_ENDERS, firstCommitSent ? 0 : 4);
        if (strong >= 0) {
            return strong + 1;
        }
        return -1;
    }

    private static int indexOfAnyFrom(String buf, String chars, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < buf.length(); i++) {
            if (chars.indexOf(buf.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private void markLlmFirstDelta() {
        if (llmFirstDeltaAtMs != 0L) {
            return;
        }
        llmFirstDeltaAtMs = System.currentTimeMillis();
        log.info("Voice duplex timing conv={} stage=llm_first_delta ms={} mode={}",
                conversationId, llmFirstDeltaAtMs - turnStartedAtMs, target.mode());
    }

    private void markFirstAudio() {
        if (firstAudioAtMs != 0L) {
            return;
        }
        firstAudioAtMs = System.currentTimeMillis();
        long llmMs = llmFirstDeltaAtMs == 0L ? -1 : firstAudioAtMs - llmFirstDeltaAtMs;
        log.info("Voice duplex timing conv={} stage=tts_first_audio ms={} afterLlmMs={}",
                conversationId, firstAudioAtMs - turnStartedAtMs, llmMs);
    }

    private void cancelInFlightTurn() {
        var fut = llmFuture.getAndSet(null);
        if (fut != null) {
            fut.cancel(true);
        }
        turnBusy.set(false);
    }

    private void sendAudio(byte[] audio, String mimeHint) {
        if (audio == null || audio.length == 0 || !sink.isOpen()) {
            return;
        }
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("type", "tts.audio");
            n.put("mime", mimeHint == null || mimeHint.isBlank() ? "audio/pcm" : mimeHint);
            n.put("sampleRate", 24000);
            n.put("base64", Base64.getEncoder().encodeToString(audio));
            sink.sendText(objectMapper.writeValueAsString(n));
        } catch (Exception e) {
            log.debug("send audio failed: {}", e.toString());
        }
    }

    private void emitJson(String type, java.util.function.Consumer<ObjectNode> filler) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("type", type);
            if (filler != null) {
                filler.accept(n);
            }
            if (sink.isOpen()) {
                sink.sendText(objectMapper.writeValueAsString(n));
            }
        } catch (Exception e) {
            log.debug("emit {} failed: {}", type, e.toString());
        }
    }

    private void emitError(String code, String message) {
        emitJson("error", n -> {
            n.put("code", code);
            n.put("message", message);
        });
    }
}

package com.lianyu.service.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.service.ai.AsrStreamClient;
import com.lianyu.service.ai.DashScopeTtsRealtimeService;
import com.lianyu.service.ai.DashScopeTtsService;
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

    private static final ExecutorService TTS_CONNECT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "voice-tts-connect");
        thread.setDaemon(true);
        return thread;
    });

    private final VoiceEventSink sink;
    private final ObjectMapper objectMapper;
    private final long userId;
    private final long conversationId;
    private final VoiceCallService voiceCallService;
    private final DashScopeTtsRealtimeService ttsRealtimeService;
    private final DashScopeTtsService ttsHttpService;
    private final AsrStreamClient.Session asr;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean turnBusy = new AtomicBoolean(false);
    private final AtomicBoolean ttsFailed = new AtomicBoolean(false);
    private final AtomicBoolean audioSent = new AtomicBoolean(false);
    private final AtomicReference<DashScopeTtsRealtimeService.Session> ttsSession = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<DashScopeTtsRealtimeService.Session>> ttsConnectFuture =
            new AtomicReference<>();
    private final AtomicReference<java.util.concurrent.CompletableFuture<?>> llmFuture = new AtomicReference<>();
    private final AtomicLong ttsGeneration = new AtomicLong();
    private final Object sentenceLock = new Object();
    private final StringBuilder sentenceBuf = new StringBuilder();
    /** LLM delta 展示缓冲：marker 不泄露到前端字幕。guarded by {@link #sentenceLock} */
    private final StringBuilder llmEmitBuf = new StringBuilder();
    /** guarded by {@link #sentenceLock} */
    private boolean firstCommitSent;
    private volatile String lastPartial = "";
    private volatile long turnStartedAtMs;
    private volatile long ttsReadyAtMs;
    private volatile long llmFirstDeltaAtMs;
    private volatile long firstAudioAtMs;
    private final String petId;

    public VoiceCallDuplexSession(
            VoiceEventSink sink,
            ObjectMapper objectMapper,
            long userId,
            long conversationId,
            VoiceCallService voiceCallService,
            AsrStreamClient asrStreamClient,
            DashScopeTtsRealtimeService ttsRealtimeService,
            DashScopeTtsService ttsHttpService) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.userId = userId;
        this.conversationId = conversationId;
        this.voiceCallService = voiceCallService;
        this.ttsRealtimeService = ttsRealtimeService;
        this.ttsHttpService = ttsHttpService;
        this.petId = voiceCallService.resolveAndAssertCallPet(userId, conversationId);
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
        prewarmRealtimeTts("call-start");
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
        prewarmRealtimeTts("barge-in");
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
        ttsReadyAtMs = 0L;
        llmFirstDeltaAtMs = 0L;
        firstAudioAtMs = 0L;
        emitJson("asr.final", n -> n.put("text", userText));
        emitJson("turn.start", n -> n.put("userText", userText));
        log.info("Voice duplex timing conv={} stage=turn_start userLen={}", conversationId, userText.length());

        Thread worker = new Thread(() -> {
            try {
                StringBuilder full = new StringBuilder();
                synchronized (sentenceLock) {
                    sentenceBuf.setLength(0);
                    llmEmitBuf.setLength(0);
                    firstCommitSent = false;
                }
                ttsFailed.set(false);
                audioSent.set(false);

                DashScopeTtsRealtimeService.Session ready = ttsSession.get();
                if (ready != null && ready.isClosed()) {
                    ttsSession.compareAndSet(ready, null);
                    ready = null;
                }
                if (ready == null && ttsConnectFuture.get() == null) {
                    prewarmRealtimeTts("turn-start");
                } else if (ready != null && ready.isReady()) {
                    markTtsReady();
                }

                voiceCallService.persistUserTurn(userId, conversationId, userText);
                AiChatRequest aiRequest = voiceCallService.buildVoiceCallAiRequest(
                        userId, conversationId, userText);

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
                    feedRealtimeTts(delta);
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

                DashScopeTtsRealtimeService.Session tts = ttsSession.get();
                if ((tts == null || !tts.isReady()) && !audioSent.get()) {
                    awaitRealtimeBriefly();
                    tts = ttsSession.get();
                }
                if (tts != null && !ttsFailed.get()) {
                    flushRealtimeTts();
                    tts.finish();
                    tts.awaitFinished(45_000);
                } else if (!audioSent.get()) {
                    ttsFailed.set(true);
                    synchronized (sentenceLock) {
                        sentenceBuf.setLength(0);
                    }
                    log.info("Voice duplex TTS HTTP fallback pet={} conv={} elapsedMs={}",
                            petId, conversationId, System.currentTimeMillis() - turnStartedAtMs);
                    DashScopeTtsService.SynthesizedAudio audio =
                            ttsHttpService.synthesizeForPet(petId, spoken);
                    if (audio == null || audio.bytes() == null || audio.bytes().length == 0) {
                        emitError("TTS_ERROR", "语音合成失败，请稍后再试");
                    } else {
                        sendAudio(audio.bytes(),
                                audio.mimeType() == null ? "audio/mpeg" : audio.mimeType());
                        emitJson("tts.done", n -> {
                        });
                    }
                } else {
                    log.warn("Voice duplex realtime TTS failed after audio started pet={} conv={}",
                            petId, conversationId);
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
                releaseTurnTts();
                llmFuture.set(null);
                turnBusy.set(false);
                prewarmRealtimeTts("after-turn");
            }
        }, "voice-call-duplex");
        worker.setDaemon(true);
        worker.start();
    }

    private DashScopeTtsRealtimeService.Session connectRealtimeTts() {
        return ttsRealtimeService.startForPet(petId, new DashScopeTtsRealtimeService.AudioListener() {
            @Override
            public void onAudio(byte[] pcmOrEncoded, String mimeHint) {
                if (!turnBusy.get() && !audioSent.get()) {
                    return;
                }
                markFirstAudio();
                audioSent.set(true);
                sendAudio(pcmOrEncoded, mimeHint == null ? "audio/pcm" : mimeHint);
            }

            @Override
            public void onDone() {
                if (turnBusy.get() || audioSent.get()) {
                    emitJson("tts.done", n -> {
                    });
                }
            }

            @Override
            public void onError(String message) {
                log.warn("Voice duplex realtime TTS error pet={} conv={}: {}",
                        petId, conversationId, message);
                if (turnBusy.get()) {
                    ttsFailed.set(true);
                }
            }

            @Override
            public void onReady() {
                markTtsReady();
                drainSentenceBuf();
            }
        });
    }

    private void prewarmRealtimeTts(String reason) {
        if (closed.get()) {
            return;
        }
        DashScopeTtsRealtimeService.Session existing = ttsSession.get();
        if (existing != null && !existing.isClosed()) {
            return;
        }
        if (ttsConnectFuture.get() != null) {
            return;
        }
        long generation = ttsGeneration.incrementAndGet();
        CompletableFuture<DashScopeTtsRealtimeService.Session> future =
                CompletableFuture.supplyAsync(this::connectRealtimeTts, TTS_CONNECT_EXECUTOR);
        ttsConnectFuture.set(future);
        log.info("Voice duplex timing conv={} stage=tts_prewarm reason={}", conversationId, reason);
        future.whenComplete((session, error) -> {
            if (error != null || session == null) {
                if (generation == ttsGeneration.get()) {
                    ttsConnectFuture.compareAndSet(future, null);
                }
                return;
            }
            if (closed.get() || generation != ttsGeneration.get()) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
            ttsSession.set(session);
            drainSentenceBuf();
        });
    }

    private void releaseTurnTts() {
        ttsGeneration.incrementAndGet();
        CompletableFuture<DashScopeTtsRealtimeService.Session> future = ttsConnectFuture.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        DashScopeTtsRealtimeService.Session tts = ttsSession.getAndSet(null);
        if (tts != null) {
            try {
                tts.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void awaitRealtimeBriefly() {
        CompletableFuture<DashScopeTtsRealtimeService.Session> future = ttsConnectFuture.get();
        if (future == null || future.isDone()) {
            return;
        }
        long elapsed = System.currentTimeMillis() - turnStartedAtMs;
        long waitMs = Math.min(500L, Math.max(0L, 1600L - elapsed));
        if (waitMs <= 0L) {
            return;
        }
        try {
            future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // late connect is handled by generation/close in releaseTurnTts
        }
    }

    private void feedRealtimeTts(String delta) {
        synchronized (sentenceLock) {
            sentenceBuf.append(delta);
            drainSentenceBufLocked();
        }
    }

    private void flushRealtimeTts() {
        synchronized (sentenceLock) {
            drainSentenceBufLocked();
            String rest = stripPauseMarkers(sentenceBuf.toString()).trim();
            sentenceBuf.setLength(0);
            DashScopeTtsRealtimeService.Session tts = ttsSession.get();
            if (!rest.isBlank() && tts != null && !ttsFailed.get() && tts.isReady()) {
                tts.appendText(rest);
                tts.commit();
            }
        }
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

    private void drainSentenceBuf() {
        synchronized (sentenceLock) {
            drainSentenceBufLocked();
        }
    }

    private void drainSentenceBufLocked() {
        DashScopeTtsRealtimeService.Session tts = ttsSession.get();
        if (tts == null || ttsFailed.get() || !tts.isReady()) {
            return;
        }
        String buf = sentenceBuf.toString();
        while (true) {
            if (buf.startsWith(PAUSE_TOKEN)) {
                buf = buf.substring(PAUSE_TOKEN.length());
                continue;
            }
            int cut = nextCommitEnd(buf, firstCommitSent);
            if (cut <= 0) {
                break;
            }
            String piece = buf.substring(0, cut).trim();
            buf = buf.substring(cut);
            if (!piece.isBlank()) {
                tts.appendText(piece);
                tts.commit();
                firstCommitSent = true;
            }
        }
        sentenceBuf.setLength(0);
        sentenceBuf.append(buf);
    }

    /** 角色模型自主标记的停顿点：语音 prompt 指示其在需要换气/停顿处输出。 */
    static final String PAUSE_TOKEN = "<|pause|>";
    private static final String STRONG_ENDERS = "。！？!?\n";
    private static final String WEAK_PAUSES = "，,、；;";

    /**
     * 合成边界决策（commit 模式下每次 commit 都是一次独立合成，边界即听感停顿点）：
     * 1. 模型显式 {@link #PAUSE_TOKEN} 最高优先 —— 角色按性格/语义自己决定停顿；
     * 2. 否则首段在 ≥4 字的句末强标点切（保首音），后续每句一个合成（保连贯）；
     * 3. 逗号类弱标点不再当边界；缓冲过长才兜底切；未完整 marker 前缀永不被切开。
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
        int floor = firstCommitSent ? 0 : 4;
        int limit = firstCommitSent ? 24 : 12;
        int strong = indexOfAnyFrom(buf, STRONG_ENDERS, floor);
        if (strong >= 0 && (partial < 0 || partial > strong)) {
            return strong + 1;
        }
        if (partial > 0) {
            return partial;
        }
        if (partial == 0) {
            return -1;
        }
        if (buf.length() >= limit) {
            if (!firstCommitSent) {
                return limit;
            }
            int weak = lastIndexOfAny(buf, WEAK_PAUSES);
            return weak >= 9 ? weak + 1 : limit;
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

    private static int lastIndexOfAny(String buf, String chars) {
        for (int i = buf.length() - 1; i >= 0; i--) {
            if (chars.indexOf(buf.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private void markTtsReady() {
        if (turnStartedAtMs == 0L || ttsReadyAtMs != 0L) {
            return;
        }
        ttsReadyAtMs = System.currentTimeMillis();
        log.info("Voice duplex timing conv={} stage=tts_ready ms={}", conversationId, ttsReadyAtMs - turnStartedAtMs);
    }

    private void markLlmFirstDelta() {
        if (llmFirstDeltaAtMs != 0L) {
            return;
        }
        llmFirstDeltaAtMs = System.currentTimeMillis();
        log.info("Voice duplex timing conv={} stage=llm_first_delta ms={}",
                conversationId, llmFirstDeltaAtMs - turnStartedAtMs);
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
        releaseTurnTts();
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

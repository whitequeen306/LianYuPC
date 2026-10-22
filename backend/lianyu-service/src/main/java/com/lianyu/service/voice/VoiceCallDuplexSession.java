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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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

    /** 句子队列的收尾标记（正常片段不会是空串/不会以 NUL 开头）。按引用比较，不按内容。 */
    private static final SentenceSplitter.Segment TTS_SENTINEL =
            new SentenceSplitter.Segment("\u0000DONE", 0);

    /**
     * WebSocket 发送串行化。
     *
     * <p>Spring 的 {@code WebSocketSession.sendMessage} **不是线程安全的**：并发调用会
     * 产生交错/损坏的帧，也可能直接抛 IllegalStateException。现在至少有四个线程会往
     * 同一条连接写：ASR 回调（asr.partial）、LLM 增量（llm.delta）、按句 TTS 线程
     * （tts.audio）、回合线程（turn.start/done）。所以只把真正的 sendText 包进锁里，
     * 编码与序列化留在锁外，避免持锁做 CPU 活。
     */
    private final Object sendLock = new Object();

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
    /**
     * 上一个回合还没收尾时，用户又说完一句 —— 先把文本存这里，等当前回合结束立刻接上。
     *
     * <p>不这么做就会**直接丢掉**这半句（{@code turnBusy} CAS 失败即 return）。用户体感是
     * 「我说了它没听见 / 识别变慢」，其实是回合还在发上一句的音频。
     */
    private final AtomicReference<String> pendingUserText = new AtomicReference<>();
    private final Object sentenceLock = new Object();
    /** LLM delta 展示缓冲：marker 不泄露到前端字幕。guarded by {@link #sentenceLock} */
    private final StringBuilder llmEmitBuf = new StringBuilder();
    private volatile String lastPartial = "";
    /** 会话起点 / 最近一帧上行 PCM / 第一条 ASR 中间结果 —— 用来量 ASR 这一段到底慢在哪。 */
    private final long sessionStartedAtMs = System.currentTimeMillis();
    private volatile long lastPcmAtMs;
    private volatile long asrFirstPartialAtMs;
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
                if (asrFirstPartialAtMs == 0L && !lastPartial.isBlank()) {
                    asrFirstPartialAtMs = System.currentTimeMillis();
                    log.info("Voice duplex timing conv={} stage=asr_first_partial ms={}",
                            conversationId, asrFirstPartialAtMs - sessionStartedAtMs);
                }
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
        lastPcmAtMs = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
        // 这行直接回答「语音转文字为什么变慢」：从用户最后一帧语音到判定说完，隔了多久。
        // 归 VAD/端点检测，不归识别模型 —— 有数才分得清该改哪一段。
        log.info("Voice duplex timing conv={} stage=asr_endpoint silenceMs={} sinceStartMs={} textLen={}",
                conversationId,
                lastPcmAtMs == 0L ? -1 : now - lastPcmAtMs,
                now - sessionStartedAtMs,
                text == null ? 0 : text.length());
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
            // 上一回合还在播音频。**不能装作没听见**：把这句话存起来，等当前回合收尾立刻接上。
            pendingUserText.set(userText);
            log.info("Voice duplex turn queued conv={} len={}（上一回合还在收尾）",
                    conversationId, userText.length());
            return;
        }
        turnStartedAtMs = System.currentTimeMillis();
        llmFirstDeltaAtMs = 0L;
        firstAudioAtMs = 0L;
        emitJson("asr.final", n -> n.put("text", userText));
        emitJson("turn.start", n -> n.put("userText", userText));
        log.info("Voice duplex timing conv={} stage=turn_start userLen={}", conversationId, userText.length());

        Thread worker = new Thread(() -> {
            // 按句流水线的两个部件：
            //  · splitter  —— 在 LLM 还在吐字时就切出完整句子
            //  · ttsQueue  —— 句子按序排队；TTS 线程取一句、合成一句、喂给客户端
            // 这样第一句的合成不必等整段回复结束，播完一句时下一句通常已就绪。
            SentenceSplitter splitter = new SentenceSplitter();
            BlockingQueue<SentenceSplitter.Segment> ttsQueue = new LinkedBlockingQueue<>();
            Thread ttsWorker = target.isLocal() ? null : startTtsPipeline(ttsQueue);
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
                    if (ttsWorker != null) {
                        for (SentenceSplitter.Segment seg : splitter.accept(delta)) {
                            ttsQueue.offer(seg);
                        }
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
                    // 收尾：把切分器里剩余的半句也送去合成，然后等 TTS 线程把队列吃完。
                    for (SentenceSplitter.Segment seg : splitter.flush()) {
                        ttsQueue.offer(seg);
                    }
                    ttsQueue.offer(TTS_SENTINEL);
                    joinQuietly(ttsWorker, 180_000);
                    if (!audioSent.get()) {
                        emitError("TTS_ERROR", "语音合成失败，请稍后再试");
                    } else {
                        emitJson("tts.done", n -> {
                        });
                    }
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
                // 收尾后再接力：期间用户说的话现在才处理，保证「说过的话一定被回应」。
                String queued = pendingUserText.getAndSet(null);
                if (queued != null && !closed.get()) {
                    startTurn(queued);
                }
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
    /**
     * 起一个 TTS 线程：按序从队列取片段，逐段流式合成并喂给客户端。
     *
     * <p>为什么用队列而不是「每句起一个线程」：**音频必须严格按句序下发**。
     * 并行合成会让第 2 句的音频插到第 1 句中间，听起来就是乱码。
     * 单线程串行虽然不重叠合成，但因为一句的播放时长（2-4s）远大于下一句的首包延迟
     * （实测 0.5-0.9s），下一句在第 1 句播完前就已就绪，实际是连续的。
     *
     * <p><b>片段之间补静音</b>：两段独立合成的音频拼在一起时，模型各自的开头/结尾静音
     * 只有 ~130ms + ~175ms，比它在一次合成里自己产出的句号停顿（实测 585ms）短一半，
     * 听上去就是「该停顿的地方连读」。所以按上一段结尾的标点补
     * {@link SentenceSplitter#SENTENCE_PAUSE_MS} 等时长的静音，把边界补齐。
     * 回合最后一段的 {@code pauseAfterMs} 是 0，不会拖慢 {@code turn.done}。
     */
    private Thread startTtsPipeline(BlockingQueue<SentenceSplitter.Segment> queue) {
        Thread ttsThread = new Thread(() -> {
            try {
                int pendingGapMs = 0;
                while (!closed.get()) {
                    SentenceSplitter.Segment segment = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (segment == null) {
                        continue;
                    }
                    if (segment == TTS_SENTINEL) {
                        return;
                    }
                    if (pendingGapMs > 0 && !closed.get()) {
                        sendAudio(PcmFrameAssembler.silence(pendingGapMs), "audio/pcm");
                    }
                    streamSentence(segment.text());
                    pendingGapMs = segment.pauseAfterMs();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "voice-call-tts-" + conversationId);
        ttsThread.setDaemon(true);
        ttsThread.start();
        return ttsThread;
    }

    /** 合成一句并把 PCM 块直接下发；等这一句的流结束（或超时）再返回。 */
    private void streamSentence(String sentence) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        log.info("Voice duplex TTS sentence conv={} len={} elapsedMs={}",
                conversationId, sentence.length(), System.currentTimeMillis() - turnStartedAtMs);
        // 上游切片边界由 TCP 决定，与 16bit 样本边界无关；直接转发会让客户端把整帧
        // 高低字节对调（听感=电子杂音）。这里统一拼成样本对齐的定长帧再下发。
        PcmFrameAssembler assembler = new PcmFrameAssembler();
        PcmFrameAssembler.FrameConsumer emit = (array, offset, length) -> {
            if (length <= 0) {
                return;
            }
            if (audioSent.compareAndSet(false, true)) {
                markFirstAudio();
            }
            sendAudio(java.util.Arrays.copyOfRange(array, offset, offset + length), "audio/pcm");
        };
        voiceCallService.streamSynthesizeForTarget(target, sentence, new TtsPort.TtsChunkSink() {
            @Override
            public void onChunk(byte[] pcm) {
                assembler.push(pcm, emit);
            }

            @Override
            public void onDone() {
                assembler.flush(emit);
                done.countDown();
            }

            @Override
            public void onError(String message) {
                log.warn("Voice duplex stream TTS error conv={}: {}", conversationId, message);
                assembler.flush(emit);
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
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 字幕展示用：剥掉完整 marker；尾部疑似未完整 marker 前缀的部分留到下一个 delta 再定。 */
    private String sanitizeDeltaForEmit(String delta) {
        synchronized (sentenceLock) {
            llmEmitBuf.append(delta);
            String s = llmEmitBuf.toString().replace(SentenceSplitter.PAUSE_TOKEN, "");
            int idx = s.lastIndexOf("<|");
            if (idx >= 0 && s.length() - idx < SentenceSplitter.PAUSE_TOKEN.length()) {
                String out = s.substring(0, idx);
                llmEmitBuf.setLength(0);
                llmEmitBuf.append(s.substring(idx));
                return out;
            }
            llmEmitBuf.setLength(0);
            return s;
        }
    }

    /** marker 及残片不进 TTS、不进聊天记录。实现已移到 {@link SentenceSplitter}。 */
    private static String stripPauseMarkers(String s) {
        return SentenceSplitter.stripPauseMarkers(s);
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
            String frame = objectMapper.writeValueAsString(n);
            synchronized (sendLock) {
                sink.sendText(frame);
            }
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
                String frame = objectMapper.writeValueAsString(n);
                synchronized (sendLock) {
                    sink.sendText(frame);
                }
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

package com.lianyu.service.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.service.ai.AsrSession;
import com.lianyu.service.ai.AsrStreamClient;
import com.lianyu.service.agent.TtsPort;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.dto.AiChatRequest;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** 预热用的文本：一个字，只为让连接与模型实例进入热态。 */
    private static final String PREWARM_TEXT = "嗯";

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

    /** 通话状态机：麦克风门控、打断、排空的唯一事实来源（客户端 phase 只做展示）。 */
    private final CallStateMachine callState = new CallStateMachine();
    /**
     * 回合代数。barge/close 时自增，让旧回合的 TTS 线程与迟到音频立刻失效 ——
     * 否则被打断的那一句会继续合成/下发，用户体感就是「打断了它还在说」。
     */
    private final AtomicLong turnEpoch = new AtomicLong();
    /** 24kHz / s16le / mono：48 字节 = 1ms，用于没有 play.drained 时估算排空时刻。 */
    private static final int PCM_BYTES_PER_MS = 48;
    /** 本回合已下发的 PCM 字节数（每回合清零）。 */
    private final AtomicLong turnAudioBytes = new AtomicLong();
    /** 下发音频帧序号，客户端 play.drained 回传它便于日志对账。 */
    private final AtomicLong audioSeq = new AtomicLong();

    private final VoiceEventSink sink;
    private final ObjectMapper objectMapper;
    private final long userId;
    private final long conversationId;
    private final VoiceCallService voiceCallService;
    private final VoiceSynthesisService voiceSynthesisService;
    private final AsrSession asr;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean turnBusy = new AtomicBoolean(false);
    private final AtomicBoolean audioSent = new AtomicBoolean(false);
    /** 已向服务端发出 commit，正在等定稿结果（等到了就取消兜底计时）。 */
    private final AtomicBoolean commitPending = new AtomicBoolean(false);
    /** 最近一次真正开起来的回合的文本与时刻：用来挡住「同一句话被触发两次」。 */
    private volatile String lastTurnText = "";
    private volatile long lastTurnAtMs = 0L;
    /** 去重时间窗：commit 超时与定稿到达之间实测只差 77ms，3s 足够宽裕。 */
    private static final long DEDUP_WINDOW_MS = 3000L;
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
    private final VoiceCallOptions options;

    /** 语气词 clip 的兜底时长：客户端缺文件时按它插静音，绝不阻断。 */
    private static final java.util.Map<String, Integer> CLIP_FALLBACK_MS = java.util.Map.of(
            "pickup", 900, "ack", 500, "hmm", 450, "laugh", 900, "sigh", 800, "breath", 500);

    /** 每通电话最多主动两次：再多就从「陪你」变成「缠你」。 */
    private static final int MAX_IDLE_NUDGES = 2;
    /** 冷场检查的轮询间隔：500ms 足够准，且用户一开口下一拍就能取消。 */
    private static final long IDLE_POLL_MS = 500L;
    /** 进入 LISTENING / 用户住口 / 用户开口的时刻：冷场计时从最后一次「有人说话」算起。 */
    private volatile long listeningSinceMs = System.currentTimeMillis();
    private final AtomicInteger idleNudgeCount = new AtomicInteger();
    private volatile long lastNudgeAtMs = 0L;

    /** 通话记忆（P3）节流：有内容的回合计满 3 轮才递一次，避免把闲聊全灌进 episode。 */
    private static final int MOMENT_INTERVAL_TURNS = 3;
    /** 比这短的句子没有可记的信息量（"嗯""好"），不值得花一次模型判定。 */
    private static final int MOMENT_MIN_USER_CHARS = 8;
    /** 已完成的、用户说了有内容的话的回合数。 */
    private final AtomicInteger contentTurns = new AtomicInteger();

    /** 本回合用户上行的 PCM 字节数（48 字节 = 1ms），用来估算语速做镜像。 */
    private final AtomicLong userTurnPcmBytes = new AtomicLong();
    /** 太少的一段语音算出来的字/秒全是噪声，不参与镜像。 */
    private static final int MIRROR_MIN_SPEECH_BYTES = 19_200;
    /** 正常中文朗读约 4-5 字/秒：6 以上算急，3 以下算缓。 */
    private static final double MIRROR_FAST_CPS = 6.0;
    private static final double MIRROR_SLOW_CPS = 3.0;
    private static final double MIRROR_MAX_FACTOR = 1.05;
    private static final double MIRROR_MIN_FACTOR = 0.95;
    private static final int PCM_BYTES_PER_SECOND = 48_000;

    public VoiceCallDuplexSession(
            VoiceEventSink sink,
            ObjectMapper objectMapper,
            long userId,
            long conversationId,
            VoiceCallService voiceCallService,
            AsrStreamClient asrStreamClient,
            VoiceSynthesisService voiceSynthesisService) {
        this(sink, objectMapper, userId, conversationId, voiceCallService, asrStreamClient,
                voiceSynthesisService, VoiceCallOptions.defaults());
    }

    public VoiceCallDuplexSession(
            VoiceEventSink sink,
            ObjectMapper objectMapper,
            long userId,
            long conversationId,
            VoiceCallService voiceCallService,
            AsrStreamClient asrStreamClient,
            VoiceSynthesisService voiceSynthesisService,
            VoiceCallOptions options) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.userId = userId;
        this.conversationId = conversationId;
        this.voiceCallService = voiceCallService;
        this.voiceSynthesisService = voiceSynthesisService;
        this.options = options == null ? VoiceCallOptions.defaults() : options;
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
                triggerTurnFromAsr("engine");
            }

            @Override
            public void onNoise() {
                // 端点成立但内容是噪声（回声尾巴）：丢掉累积文本、取消等定稿的兜底计时，绝不开回合。
                synchronized (VoiceCallDuplexSession.this) {
                    lastPartial = "";
                }
                commitPending.set(false);
                log.info("Voice duplex asr noise dropped conv={}", conversationId);
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
        prewarmTts();
        startIdleWatcher();
    }

    /**
     * 通话建立时先把 TTS 焐热：用**当前角色的音色**合成一个字，把音频丢掉。
     *
     * <p>为什么值得：首声 = LLM 出第一个字 + TTS 首包。TTS 首包的冷/热差距是实测的：
     * 冷（新 TCP+TLS + 模型实例冷启）1163-2162ms，热 588-862ms。而在一次通话里，
     * 第一句话永远是冷的。
     *
     * <p>发**两次**：agent 前置了负载均衡，两个实例各自持有独立的 httpx 连接池，
     * 只焐一个等于有一半概率白做。成本是每次通话 2 个字（约 0.0002 元），可以忽略。
     *
     * <p>失败无所谓 —— 预热本来就是为了快，不能让它影响通话。
     */
    private void prewarmTts() {
        prewarmTtsInternal(2);
    }

    /**
     * 每轮对话开始前焐一次（异步、只一次）。
     *
     * <p>为什么还需要它：通话建立时那次预热只保住了第一句。DashScope 的 HTTP 合成不是
     * 长连接保活，两次调用间隔久了模型实例会冷却 —— 线上实测同一通电话里
     * 后续轮次的首包回到 1940ms（热态是 588ms）。
     *
     * <p>关键点是**并行**：它和 LLM 出首字同时开始，等句子切好（约 1s 后）模型实例已就绪，
     * 用户几乎听不到这次预热的存在；如果串行做就纯亏时间。
     *
     * <p>只焐一次（不像通话建立时发两次）：这时负载均衡另一侧会被正式合成打到，不必重复。
     */
    private void prewarmTtsForTurn() {
        if (target.isLocal() || closed.get()) {
            return;
        }
        Thread t = new Thread(() -> prewarmTtsInternal(1), "voice-prewarm-" + conversationId);
        t.setDaemon(true);
        t.start();
    }

    private void prewarmTtsInternal(int times) {
        if (target.isLocal()) {
            return;
        }
        for (int i = 0; i < times; i++) {
            try {
                voiceCallService.streamSynthesizeForTarget(target, PREWARM_TEXT, new TtsPort.TtsChunkSink() {
                    @Override
                    public void onChunk(byte[] pcm) {
                        // 丢弃：只为把连接和模型实例焐热，不是要出声
                    }

                    @Override
                    public void onDone() {
                    }

                    @Override
                    public void onError(String message) {
                        log.debug("Voice duplex TTS prewarm failed conv={}: {}", conversationId, message);
                    }
                });
            } catch (Exception e) {
                log.debug("Voice duplex TTS prewarm skipped conv={}: {}", conversationId, e.toString());
            }
        }
    }

    public void onPcm(byte[] pcm) {
        if (closed.get() || pcm == null || pcm.length == 0) {
            return;
        }
        lastPcmAtMs = System.currentTimeMillis();
        // 门控交给状态机：角色出声（SPEAKING/BACKCHANNEL）时麦克风不进 ASR ——
        // 外放的声音会被当成「用户说的话」，线上表现就是下一句转写出一堆重复文字。
        // 打断所需的 pre-roll 由客户端在 barge_in 后回灌，不依赖服务端保帧。
        if (!callState.micForwarded()) {
            return;
        }
        asr.sendPcm(pcm);
        userTurnPcmBytes.addAndGet(pcm.length);
    }

    /**
     * 真人插话（客户端 VAD 判定后发来）。
     *
     * <p>{@code preRollMs} 是客户端回灌的历史语音长度，这里只用于日志：回灌帧到达时
     * 状态已切到 LISTENING，走正常 {@link #onPcm} 即可，服务端不需要自己留音频。
     */
    public void bargeIn(int preRollMs) {
        if (closed.get() || !callState.canBargeIn()) {
            log.info("Voice duplex barge ignored conv={} state={}", conversationId, callState.state());
            return;
        }
        emitState(callState.on(CallStateMachine.Event.BARGE_IN, "barge_in"));
        // 先作废在途回合（LLM + 已排队的 TTS 音频），再放行麦克风。
        turnEpoch.incrementAndGet();
        cancelInFlightTurn();
        // **必须清去重基线**：pre-roll 回灌的正是被打断那半句之前的话，
        // 不清的话 isDuplicateOfLastTurn 会把它当成上一轮重复直接丢掉（线上「打断后没反应」）。
        lastTurnText = "";
        lastTurnAtMs = 0L;
        emitJson("turn.cancelled", n -> n.put("preRollMs", preRollMs));
        emitState(callState.on(CallStateMachine.Event.TURN_CANCELLED, "barge_in"));
        log.info("Voice duplex barge conv={} preRollMs={}", conversationId, preRollMs);
    }

    /**
     * 客户端 VAD 边沿。
     *
     * <p>{@code start} 用于两件事：驱动 LISTENING → USER_SPEAKING（否则服务端只知道
     * 引擎端点，思考期插话无法生效）；若上一刻是 THINKING，说明用户等不及回答开口了，
     * 立刻取消在途回合。{@code end} 把 USER_SPEAKING 收回 THINKING。
     */
    public void onVad(String phase) {
        if (closed.get()) {
            return;
        }
        if ("start".equals(phase)) {
            // 用户开口：冷场计时归零，正在生成的 idle 台词会因代数变化而作废
            listeningSinceMs = System.currentTimeMillis();
            CallStateMachine.Transition t = callState.on(CallStateMachine.Event.VAD_START, "vad.start");
            if (t.changed() && t.from() == CallStateMachine.State.THINKING) {
                turnEpoch.incrementAndGet();
                cancelInFlightTurn();
                emitJson("turn.cancelled", n -> n.put("reason", "user_speaking"));
            }
            emitState(t);
        } else if ("end".equals(phase)) {
            // 住口 = 静默开始计时的起点
            listeningSinceMs = System.currentTimeMillis();
            emitState(callState.on(CallStateMachine.Event.VAD_END, "vad.end"));
        }
    }

    /**
     * 客户端上报「播放队列已排空」。
     *
     * <p>只有 SPEAKING 才受理：其他状态下的 drained 可能来自上一回合的迟到帧，
     * 受理会把状态误推成 LISTENING、提前放行麦克风（回声被当成用户说话）。
     */
    public void onPlayDrained(long seq) {
        if (closed.get()) {
            return;
        }
        CallStateMachine.Transition t = callState.on(CallStateMachine.Event.PLAY_DRAINED, "play.drained");
        if (t.changed()) {
            log.info("Voice duplex play drained conv={} seq={} serverAudioSeq={}",
                    conversationId, seq, audioSeq.get());
            emitState(t);
        }
    }

    /**
     * 客户端链路指标（P0 只落日志）：用来回答「打断后多久出第一条 partial」。
     */
    public void onClientMetrics(JsonNode metrics) {
        if (metrics == null) {
            return;
        }
        log.info("Voice duplex client metrics conv={} firstAudioMs={} bargeToPartialMs={} underruns={} droppedMs={}",
                conversationId,
                metrics.path("firstAudioMs").asLong(-1),
                metrics.path("bargeToPartialMs").asLong(-1),
                metrics.path("underruns").asLong(-1),
                metrics.path("droppedMs").asLong(-1));
    }

    /** 会话建立后先下发一次全量状态，客户端不必等第一次迁移才有镜像。 */
    public void announceState() {
        CallStateMachine.State s = callState.state();
        long seq = callState.seq();
        emitJson("call.state", n -> {
            n.put("seq", seq);
            n.put("state", s.name());
            n.put("reason", "session.start");
        });
    }

    /**
     * 接通后的主动开场：先给一声「接起电话」的语气词（0 延迟），再并行生成一句
     * contextual 开场白走 TTS。
     *
     * <p>用户先开口 = 立即取消：VAD start / barge 会自增回合代数并取消在途回合，
     * 本方法的工作线程看到代数变化就静默退出，不落库、不出声。
     */
    public void startOpening() {
        if (!options.openingEnabled() || closed.get()) {
            return;
        }
        if (!turnBusy.compareAndSet(false, true)) {
            return;
        }
        long epoch = turnEpoch.incrementAndGet();
        audioSent.set(false);
        turnAudioBytes.set(0);
        firstAudioAtMs = 0L;
        turnStartedAtMs = System.currentTimeMillis();
        emitState(callState.on(CallStateMachine.Event.OPENING_BEGIN, "opening"));
        sendClip("pickup", epoch);

        Thread worker = new Thread(() -> {
            String text = null;
            try {
                text = voiceCallService.generateVoiceCue(userId, conversationId, "opening");
            } catch (Exception e) {
                log.debug("Voice opening cue skipped conv={}: {}", conversationId, e.toString());
            }
            if (text == null || text.isBlank()) {
                log.info("Voice opening cue empty conv={}", conversationId);
                finishOpeningSilently(epoch);
                return;
            }
            if (closed.get() || epoch != turnEpoch.get()) {
                // 用户先开口了：不落库、不出声
                return;
            }
            try {
                String spoken = InnerThoughtFilter.strip(
                        SentenceSplitter.stripPauseMarkers(text.trim()));
                if (spoken.isBlank()) {
                    finishOpeningSilently(epoch);
                    return;
                }
                spoken = voiceCallService.clampReply(spoken);
                String openingText = spoken;
                voiceCallService.persistAssistantTurn(userId, conversationId, openingText);
                emitJson("turn.start", n -> {
                    n.put("userText", "");
                    n.put("kind", "opening");
                });
                if (!synthesizeWhole(openingText, epoch, prosodyForReply())) {
                    return;
                }
                emitJson("turn.done", n -> {
                    n.put("replyText", openingText);
                    n.put("kind", "opening");
                });
                log.info("Voice opening delivered conv={} len={}", conversationId, openingText.length());
            } catch (Exception e) {
                log.warn("Voice opening failed conv={}: {}", conversationId, e.toString());
                forceListening("opening_error");
            } finally {
                if (epoch == turnEpoch.get()) {
                    turnBusy.set(false);
                }
            }
        }, "voice-opening-" + conversationId);
        worker.setDaemon(true);
        worker.start();
    }

    /** 开场拿不到文案：安静回到听，别让客户端一直停在「正在想」。 */
    private void finishOpeningSilently(long epoch) {
        if (closed.get() || epoch != turnEpoch.get()) {
            return;
        }
        turnBusy.set(false);
        if (callState.state() == CallStateMachine.State.SPEAKING) {
            // pickup clip 可能已经在播：给它播完的时间再放麦克风
            scheduleDrainAfter(epoch, 1500, "opening_empty");
        } else {
            forceListening("opening_empty");
        }
    }

    /**
     * 冷场主动开口（P2）：安静超过 {@link VoiceCallOptions#idleMs()} 时，让角色轻轻递一句话。
     *
     * <p>为什么用轮询而不是定时器：静默起点会被「用户开口 / 角色说完 / 用户插话」反复刷新，
     * 定时器每刷新一次都要取消重建；500ms 轮一次更简单，用户一开口最迟半秒就会错过触发。
     *
     * <p>三重闸门：每通最多 {@link #MAX_IDLE_NUDGES} 次、两次之间不小于
     * {@link VoiceCallOptions#idleMinGapMs()}、只在 LISTENING 且回合空闲时才敢开口。
     */
    private void startIdleWatcher() {
        if (!options.idleNudgeEnabled()) {
            return;
        }
        Thread watcher = new Thread(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                maybeIdleNudge();
            }
        }, "voice-idle-" + conversationId);
        watcher.setDaemon(true);
        watcher.start();
    }

    private void maybeIdleNudge() {
        if (closed.get() || turnBusy.get()) {
            return;
        }
        if (callState.state() != CallStateMachine.State.LISTENING) {
            return;
        }
        long now = System.currentTimeMillis();
        long silentMs = now - listeningSinceMs;
        if (silentMs < options.idleMs()) {
            return;
        }
        if (idleNudgeCount.get() >= MAX_IDLE_NUDGES) {
            return;
        }
        if (lastNudgeAtMs > 0 && now - lastNudgeAtMs < options.idleMinGapMs()) {
            return;
        }
        if (!turnBusy.compareAndSet(false, true)) {
            return;
        }
        idleNudgeCount.incrementAndGet();
        lastNudgeAtMs = now;
        listeningSinceMs = now;
        long epoch = turnEpoch.incrementAndGet();
        turnStartedAtMs = now;
        llmFirstDeltaAtMs = 0L;
        firstAudioAtMs = 0L;
        turnAudioBytes.set(0);
        audioSent.set(false);
        emitState(callState.on(CallStateMachine.Event.IDLE_TRIGGER, "idle"));
        log.info("Voice duplex idle nudge conv={} count={} silentMs={}",
                conversationId, idleNudgeCount.get(), silentMs);
        Thread worker = new Thread(() -> speakIdleNudge(epoch), "voice-idle-cue-" + conversationId);
        worker.setDaemon(true);
        worker.start();
    }

    private void speakIdleNudge(long epoch) {
        try {
            String text = voiceCallService.generateVoiceCue(userId, conversationId, "idle");
            if (text == null || text.isBlank()) {
                // Python 可能按关系阶段决定「这次不必开口」（TESTING）—— 静默退回听
                finishIdleSilently(epoch);
                return;
            }
            if (closed.get() || epoch != turnEpoch.get()) {
                return;
            }
            String spoken = InnerThoughtFilter.strip(
                    SentenceSplitter.stripPauseMarkers(text.trim()));
            if (spoken.isBlank()) {
                finishIdleSilently(epoch);
                return;
            }
            spoken = voiceCallService.clampReply(spoken);
            String idleText = spoken;
            voiceCallService.persistAssistantTurn(userId, conversationId, idleText);
            emitJson("turn.start", n -> {
                n.put("userText", "");
                n.put("kind", "idle");
            });
            if (!synthesizeWhole(idleText, epoch, prosodyForReply())) {
                return;
            }
            emitJson("turn.done", n -> {
                n.put("replyText", idleText);
                n.put("kind", "idle");
            });
            log.info("Voice duplex idle delivered conv={} len={}", conversationId, idleText.length());
        } catch (Exception e) {
            log.warn("Voice duplex idle failed conv={}: {}", conversationId, e.toString());
            forceListening("idle_error");
        } finally {
            if (epoch == turnEpoch.get()) {
                turnBusy.set(false);
            }
        }
    }

    private void finishIdleSilently(long epoch) {
        if (closed.get() || epoch != turnEpoch.get()) {
            return;
        }
        turnBusy.set(false);
        forceListening("idle_empty");
    }

    /**
     * 通话记忆（P3）：只有「用户说了 ≥8 个字」的回合才计数，每 3 轮递一次。
     *
     * <p>开关与「值不值得记」都在 VoiceCallService / Python；这里只做节流 ——
     * 每一轮都花一次模型判定，既慢又会把寒暄也灌进 episode。
     */
    private void maybeRecordVoiceMoment(String userText, String replyText) {
        String text = userText == null ? "" : userText.trim();
        if (text.length() < MOMENT_MIN_USER_CHARS) {
            return;
        }
        if (contentTurns.incrementAndGet() % MOMENT_INTERVAL_TURNS != 0) {
            return;
        }
        try {
            voiceCallService.recordVoiceMoment(userId, conversationId, text, replyText);
        } catch (Exception e) {
            log.debug("Voice moment skipped conv={}: {}", conversationId, e.toString());
        }
    }

    /**
     * 语速镜像（P3）：按用户本轮的字/秒微调角色语速，最多 ±5%。
     *
     * <p>只改 rate、不改停顿：停顿是情绪的一部分（难过时慢慢说），跟着语速动会把它抹平。
     * 语音太短（<0.4s）或字数太少时不镜像 —— 那点样本算出来的 cps 全是噪声。
     */
    private VoiceProsody applyRateMirror(VoiceProsody prosody, String userText, long speechBytes) {
        if (!options.rateMirrorEnabled() || speechBytes < MIRROR_MIN_SPEECH_BYTES) {
            return prosody;
        }
        int chars = meaningfulLength(userText);
        if (chars < 2) {
            return prosody;
        }
        double seconds = speechBytes / (double) PCM_BYTES_PER_SECOND;
        double cps = chars / seconds;
        double factor;
        if (cps >= MIRROR_FAST_CPS) {
            factor = MIRROR_MAX_FACTOR;
        } else if (cps <= MIRROR_SLOW_CPS) {
            factor = MIRROR_MIN_FACTOR;
        } else {
            double t = (cps - MIRROR_SLOW_CPS) / (MIRROR_FAST_CPS - MIRROR_SLOW_CPS);
            factor = MIRROR_MIN_FACTOR + t * (MIRROR_MAX_FACTOR - MIRROR_MIN_FACTOR);
        }
        log.info("Voice rate mirror conv={} chars={} ms={} cps={} factor={}",
                conversationId, chars, speechBytes / PCM_BYTES_PER_MS,
                String.format("%.2f", cps), String.format("%.3f", factor));
        return prosody.withRateFactor(factor);
    }

    /**
     * 本回合该用的韵律：开关关着就中性（不发多余参数），查询失败也退回中性。
     *
     * <p>刻意放在工作线程里调用：它要读一次角色状态，不能占着 WebSocket 线程。
     */
    private VoiceProsody prosodyForReply() {
        if (!options.emotionProsodyEnabled()) {
            return VoiceProsody.NEUTRAL;
        }
        try {
            return voiceCallService.resolveProsody(userId, conversationId);
        } catch (Exception e) {
            log.debug("Voice prosody skipped conv={}: {}", conversationId, e.toString());
            return VoiceProsody.NEUTRAL;
        }
    }

    /**
     * 把一段完整文本走「切句 → 逐句 TTS → tts.done」流程（开场/冷场等脚本化台词）。
     *
     * @return false 表示被打断或 TTS 失败（调用方不要再发 turn.done）
     */
    private boolean synthesizeWhole(String text, long epoch, VoiceProsody prosody) {
        if (target.isLocal()) {
            emitLocalTts(text, epoch);
            return epoch == turnEpoch.get();
        }
        SentenceSplitter splitter = new SentenceSplitter(prosody.pauseScale());
        BlockingQueue<SentenceSplitter.Segment> queue = new LinkedBlockingQueue<>();
        Thread ttsWorker = startTtsPipeline(queue, epoch, prosody);
        for (SentenceSplitter.Segment seg : splitter.accept(text)) {
            queue.offer(seg);
        }
        for (SentenceSplitter.Segment seg : splitter.flush()) {
            queue.offer(seg);
        }
        queue.offer(TTS_SENTINEL);
        joinQuietly(ttsWorker, 120_000);
        if (epoch != turnEpoch.get()) {
            return false;
        }
        if (!audioSent.get()) {
            emitError("TTS_ERROR", "语音合成失败，请稍后再试");
            forceListening("tts_error");
            return false;
        }
        emitJson("tts.done", n -> {
        });
        scheduleDrainEstimate(epoch);
        return true;
    }

    /**
     * 客户端 VAD 判定「说完了」。
     *
     * <p>不直接取 {@code lastPartial} 进回合：那是**中间结果**，引擎往往还没把尾巴解出来，
     * 直接拿去提问就会得到「什么赫赫尔」这种残句/乱码（线上真实反馈）。
     * 正确做法是先让服务端 commit（补尾音 + 解完 + SenseVoice 二次解码），
     * 定稿文本通过 {@code final}/{@code endpoint} 事件回到 {@link #triggerTurnFromAsr}。
     *
     * <p>{@link #COMMIT_WAIT_MS} 是兜底：服务端无响应（网络抖动/模型卡住）时，
     * 退回旧行为用中间结果，宁可稍差也不能让用户"说了没反应"。
     */
    public void clientEndpoint() {
        if (closed.get()) {
            return;
        }
        commitPending.set(true);
        try {
            asr.commit();
        } catch (Exception e) {
            commitPending.set(false);
            triggerTurnFromAsr("client-nocommit");
            return;
        }
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(COMMIT_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (commitPending.compareAndSet(true, false)) {
                log.info("Voice duplex commit timeout conv={}, falling back to partial", conversationId);
                triggerTurnFromAsr("client-timeout");
            }
        }, "voice-commit-" + conversationId);
        timer.setDaemon(true);
        timer.start();
    }

    /** commit 定稿的等待上限。线上实测 677ms（含 SenseVoice 二次解码），原来 600ms 会误判超时。 */
    private static final long COMMIT_WAIT_MS = 1500L;

    /**
     * 端点触发：把已累积的 ASR 文本变成一个回合。
     *
     * <p>两路来源：
     * <ul>
     *   <li>{@code client} —— 客户端 VAD（750ms 静音）判定说完，实测最后一帧到端点只差
     *       3-40ms，是最快也最准的一路；</li>
     *   <li>{@code engine} —— sherpa-onnx Zipformer 的端点规则。⚠️ 它的 rule1 是
     *       <b>「纯静音满 {@code ASR_EP_RULE1}=2.4s 就报端点」</b>，<b>与有没有说话无关</b>。
     *       通话中麦克风一直在推流，所以引擎会每 2.4-2.7 秒稳定吐一个**空端点</b>
     *       （2026-10-22 生产日志：34.251 / 36.956 / 39.713 / 42.465 / 45.201，间隔 2.74s，全部 textLen=0）。</li>
     * </ul>
     *
     * <p>空端点必须**什么都不做**，原因有二：
     * <ol>
     *   <li>清 {@code lastPartial} 会切掉正在累积的半句（用户停顿一下就被判成一句）；</li>
     *   <li>{@code asr.reset()} 会清空 Zipformer 的声学上下文 —— 而服务端在发 {@code endpoint}
     *       之前**已经自己 reset 过**（{@code deploy/asr/server.py}），这里再 reset 一次纯属
     *       把下一次发声的左侧上下文白扔掉，句子开头凭空变差。</li>
     * </ol>
     */
    private void triggerTurnFromAsr(String source) {
        // 服务端的定稿回来了（final/endpoint）：取消「等不到就退回中间结果」的兜底
        commitPending.set(false);
        long now = System.currentTimeMillis();
        int textLen;
        String text;
        synchronized (this) {
            textLen = lastPartial == null ? 0 : lastPartial.length();
            boolean hasText = textLen > 0 && !lastPartial.isBlank();
            text = hasText ? lastPartial : null;
            if (hasText) {
                lastPartial = "";
            }
        }
        // 这行直接回答「语音转文字为什么变慢」：从用户最后一帧语音到判定说完，隔了多久。
        // 实测 3-40ms，说明端点判定不是瓶颈。
        log.info("Voice duplex timing conv={} stage=asr_endpoint source={} silenceMs={} sinceStartMs={} textLen={}",
                conversationId,
                source,
                lastPcmAtMs == 0L ? -1 : now - lastPcmAtMs,
                now - sessionStartedAtMs,
                textLen);
        if (text == null) {
            // 空端点（引擎的纯静音规则会周期性触发）：没有可开回合的文本，
            // 把 THINKING 收回 LISTENING，否则客户端会一直显示「正在想」。
            forceListening("empty_endpoint");
            return;
        }
        try {
            // 正常路径（commit 定稿 / 引擎端点）服务端已经自己 reset 过，这里再 reset 一次
            // 只会把下一句的左侧上下文白扔掉；只有兜底路径（没走 commit 就进回合）才需要补。
            if (source.startsWith("client")) {
                asr.reset();
            }
        } catch (Exception e) {
            log.debug("ASR reset after call endpoint failed: {}", e.toString());
        }
        startTurn(text);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        turnEpoch.incrementAndGet();
        cancelInFlightTurn();
        asr.close();
    }

    /**
     * 把状态收回 LISTENING（异常/空文本等收尾路径）。
     *
     * <p>状态机只认事件：THINKING 用 TURN_CANCELLED 收回，SPEAKING 用 PLAY_DRAINED 收回；
     * 其余状态本来就能收麦克风，不需要迁移。
     */
    private void forceListening(String reason) {
        CallStateMachine.State s = callState.state();
        if (s == CallStateMachine.State.SPEAKING) {
            emitState(callState.on(CallStateMachine.Event.PLAY_DRAINED, reason));
        } else if (s == CallStateMachine.State.THINKING
                || s == CallStateMachine.State.USER_SPEAKING
                || s == CallStateMachine.State.INTERRUPTED) {
            emitState(callState.on(CallStateMachine.Event.TURN_CANCELLED, reason));
        }
    }

    /** 状态迁移的唯一出口：只在真的变了时下发 {@code call.state}。 */
    private void emitState(CallStateMachine.Transition t) {
        if (t == null || !t.changed()) {
            return;
        }
        if (t.to() == CallStateMachine.State.LISTENING) {
            // 回到「听」= 又一段静默的起点（角色刚说完，用户还没开口）
            listeningSinceMs = System.currentTimeMillis();
        }
        log.info("Voice duplex state conv={} seq={} {}->{} reason={}",
                conversationId, t.seq(), t.from(), t.to(), t.reason());
        emitJson("call.state", n -> {
            n.put("seq", t.seq());
            n.put("state", t.to().name());
            n.put("reason", t.reason() == null ? "" : t.reason());
        });
    }

    /** 去掉标点/空白后的字数（回声尾巴常常只剩标点加一两个字）。 */
    private static int meaningfulLength(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private static String stripPunct(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 是否与「刚开起来的那个回合」是同一句话。
     *
     * <p>不做严格相等：定稿通常比中间结果多几个字（线上实测 12 字 vs 14 字），
     * 所以用「时间窗 + 包含关系 + 前缀重合度」判定，宁可漏挡也不要误挡下一句。
     */
    private boolean isDuplicateOfLastTurn(String text) {
        String prev = lastTurnText;
        if (prev == null || prev.isBlank()) {
            return false;
        }
        if (System.currentTimeMillis() - lastTurnAtMs > DEDUP_WINDOW_MS) {
            return false;
        }
        String a = stripPunct(prev);
        String b = stripPunct(text);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        int min = Math.min(a.length(), b.length());
        int common = 0;
        while (common < min && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common >= Math.max(2, (int) Math.ceil(min * 0.6));
    }

    private static String abbreviate(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "…";
    }

    private void startTurn(String rawText) {
        if (closed.get()) {
            return;
        }
        String userText = UserInputSanitizer.sanitizeChatMessage(
                rawText == null ? "" : rawText).storedText();
        if (userText.isBlank()) {
            if (!turnBusy.get()) {
                forceListening("blank_text");
            }
            return;
        }
        // 噪声过滤：定稿只剩一两个字（回声尾巴、咳嗽、桌面杂音）不值得开一整回合。
        if (meaningfulLength(userText) < 2) {
            log.info("Voice duplex skip noise conv={} len={} text={}",
                    conversationId, userText.length(), abbreviate(userText));
            if (!turnBusy.get()) {
                forceListening("noise");
            }
            return;
        }
        // 同一句话的重复触发：commit 超时先拿中间结果开了回合、定稿随后才到（线上实测差 77ms），
        // 以及客户端 VAD 抖动重复发端点。不挡的话用户会听到「同一句被处理两次」。
        if (isDuplicateOfLastTurn(userText)) {
            log.info("Voice duplex skip duplicate conv={} len={} text={}",
                    conversationId, userText.length(), abbreviate(userText));
            if (!turnBusy.get()) {
                forceListening("duplicate");
            }
            return;
        }
        if (!turnBusy.compareAndSet(false, true)) {
            // 上一回合还在播音频。**不能装作没听见**：把这句话存起来，等当前回合收尾立刻接上。
            pendingUserText.set(userText);
            log.info("Voice duplex turn queued conv={} len={}（上一回合还在收尾）",
                    conversationId, userText.length());
            return;
        }
        long epoch = turnEpoch.incrementAndGet();
        // 取走本轮语音长度：语速镜像要用，取走后下一轮重新累计
        final long speechBytes = userTurnPcmBytes.getAndSet(0);
        lastTurnText = userText;
        lastTurnAtMs = System.currentTimeMillis();
        turnStartedAtMs = System.currentTimeMillis();
        // 用户刚说完，冷场计时从现在开始
        listeningSinceMs = turnStartedAtMs;
        llmFirstDeltaAtMs = 0L;
        firstAudioAtMs = 0L;
        turnAudioBytes.set(0);
        audioSent.set(false);
        // 用户说完 → THINKING。老客户端不发 vad 边沿，这里先补一次「开始说话」，
        // 否则从 LISTENING 直接来端点时状态机不会迁移，客户端看不到「正在想」。
        if (callState.state() == CallStateMachine.State.LISTENING) {
            emitState(callState.on(CallStateMachine.Event.ASR_PARTIAL, "turn_begin"));
        }
        emitState(callState.on(CallStateMachine.Event.ENDPOINT, "turn_begin"));
        emitJson("asr.final", n -> n.put("text", userText));
        emitJson("turn.start", n -> n.put("userText", userText));
        log.info("Voice duplex timing conv={} stage=turn_start userLen={}", conversationId, userText.length());

        // 与下面的 LLM 请求**并行**把 TTS 焐热：等句子切好（约 1s 后）模型实例就绪，
        // 首包从冷态的 ~1.9s 降到热态的 ~0.6s。
        prewarmTtsForTurn();

        Thread worker = new Thread(() -> {
            // 按句流水线的两个部件：
            //  · splitter  —— 在 LLM 还在吐字时就切出完整句子
            //  · ttsQueue  —— 句子按序排队；TTS 线程取一句、合成一句、喂给客户端
            // 这样第一句的合成不必等整段回复结束，播完一句时下一句通常已就绪。
            // 韵律在**本线程**取（要读角色状态），不占 WebSocket 线程。
            VoiceProsody prosody = applyRateMirror(prosodyForReply(), userText, speechBytes);
            SentenceSplitter splitter = new SentenceSplitter(prosody.pauseScale());
            BlockingQueue<SentenceSplitter.Segment> ttsQueue = new LinkedBlockingQueue<>();
            Thread ttsWorker = target.isLocal() ? null : startTtsPipeline(ttsQueue, epoch, prosody);
            try {
                StringBuilder full = new StringBuilder();
                synchronized (sentenceLock) {
                    llmEmitBuf.setLength(0);
                }

                long persistStart = System.currentTimeMillis();
                voiceCallService.persistUserTurn(userId, conversationId, userText);
                AiChatRequest aiRequest = voiceCallService.buildVoiceCallAiRequest(
                        userId, conversationId, userText);
                log.info("Voice duplex timing conv={} stage=persist_and_build ms={}",
                        conversationId, System.currentTimeMillis() - persistStart);

                var future = voiceCallService.streamVoiceReply(userId, aiRequest, delta -> {
                    if (delta == null || delta.isEmpty() || closed.get() || epoch != turnEpoch.get()) {
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
                if (closed.get() || epoch != turnEpoch.get() || Thread.currentThread().isInterrupted()) {
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
                    emitLocalTts(spoken, epoch);
                } else {
                    // 收尾：把切分器里剩余的半句也送去合成，然后等 TTS 线程把队列吃完。
                    for (SentenceSplitter.Segment seg : splitter.flush()) {
                        ttsQueue.offer(seg);
                    }
                    ttsQueue.offer(TTS_SENTINEL);
                    joinQuietly(ttsWorker, 180_000);
                    if (epoch != turnEpoch.get()) {
                        return;
                    }
                    if (!audioSent.get()) {
                        emitError("TTS_ERROR", "语音合成失败，请稍后再试");
                        forceListening("tts_error");
                    } else {
                        emitJson("tts.done", n -> {
                        });
                        // 正常由客户端 play.drained 收回 LISTENING；丢包/老客户端收不到时按字节估算兜底
                        scheduleDrainEstimate(epoch);
                    }
                }

                if (epoch != turnEpoch.get()) {
                    return;
                }
                String reply = spoken;
                long totalMs = System.currentTimeMillis() - turnStartedAtMs;
                log.info("Voice duplex timing conv={} stage=turn_done totalMs={} firstAudioMs={}",
                        conversationId, totalMs, firstAudioAtMs == 0L ? -1 : firstAudioAtMs - turnStartedAtMs);
                emitJson("turn.done", n -> {
                    n.put("userText", userText);
                    n.put("replyText", reply);
                });
                maybeRecordVoiceMoment(userText, reply);
            } catch (CancellationException e) {
                // 打断/思考期插话会主动 cancel：这是预期路径，不是错误
                log.info("Voice duplex turn cancelled conv={}", conversationId);
            } catch (Exception e) {
                log.warn("Voice duplex turn failed: {}", e.toString());
                emitError("TURN_ERROR", "角色暂时无法回复，请稍后再试");
                forceListening("turn_error");
            } finally {
                // 只有「本回合仍是当前回合」才做收尾：被打断后新回合可能已经开跑，
                // 旧线程再清 turnBusy / llmFuture 会把新回合的状态搅坏。
                if (epoch == turnEpoch.get()) {
                    llmFuture.set(null);
                    turnBusy.set(false);
                    // 收尾后再接力：期间用户说的话现在才处理，保证「说过的话一定被回应」。
                    String queued = pendingUserText.getAndSet(null);
                    if (queued != null && !closed.get()) {
                        startTurn(queued);
                    }
                }
            }
        }, "voice-call-duplex");
        worker.setDaemon(true);
        worker.start();
    }

    public VoiceCallTarget getTarget() {
        return target;
    }

    private void emitLocalTts(String spoken, long epoch) {
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
        // 本地音色由客户端合成，服务端没有 PCM 字节可算时长：按字数粗估兜底。
        // 老客户端不发 play.drained，没有这个兜底会永久停在 SPEAKING（麦克风一直关着）。
        scheduleDrainAfter(epoch, Math.max(1500, spoken.length() * 220L) + 1500, "drain_local_estimate");
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
    private Thread startTtsPipeline(BlockingQueue<SentenceSplitter.Segment> queue, long epoch,
                                    VoiceProsody prosody) {
        Thread ttsThread = new Thread(() -> {
            try {
                int pendingGapMs = 0;
                // epoch 变化 = 这一回合被打断：立刻停手，别把旧的句子继续合成出来
                while (!closed.get() && epoch == turnEpoch.get()) {
                    SentenceSplitter.Segment segment = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (segment == null) {
                        continue;
                    }
                    if (segment == TTS_SENTINEL) {
                        return;
                    }
                    if (epoch != turnEpoch.get()) {
                        return;
                    }
                    if (pendingGapMs > 0 && !closed.get()) {
                        sendAudio(PcmFrameAssembler.silence(pendingGapMs), "audio/pcm", epoch);
                    }
                    if (segment.isClip()) {
                        // 语气词走预录 clip：不经过 TTS 往返，毫秒级出声
                        sendClip(segment.text(), epoch);
                        pendingGapMs = 0;
                        continue;
                    }
                    streamSentence(segment.text(), epoch, prosody);
                    pendingGapMs = jitteredPause(segment.pauseAfterMs());
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
    private void streamSentence(String sentence, long epoch, VoiceProsody prosody) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        log.info("Voice duplex TTS sentence conv={} len={} elapsedMs={}",
                conversationId, sentence.length(), System.currentTimeMillis() - turnStartedAtMs);
        // 上游切片边界由 TCP 决定，与 16bit 样本边界无关；直接转发会让客户端把整帧
        // 高低字节对调（听感=电子杂音）。这里统一拼成样本对齐的定长帧再下发。
        PcmFrameAssembler assembler = new PcmFrameAssembler();
        PcmFrameAssembler.FrameConsumer emit = (array, offset, length) -> {
            if (length <= 0 || epoch != turnEpoch.get()) {
                return;
            }
            if (audioSent.compareAndSet(false, true)) {
                markFirstAudio();
            }
            sendAudio(java.util.Arrays.copyOfRange(array, offset, offset + length), "audio/pcm", epoch);
        };
        voiceCallService.streamSynthesizeForTarget(target, sentence, prosody, new TtsPort.TtsChunkSink() {
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

    /**
     * 给片段之间的静音加 ±40ms 抖动。
     *
     * <p>固定间隔听久了就是节拍器 —— 真人说话的停顿从来不是等长的。抖动放在这里而不是
     * {@link SentenceSplitter}，是为了让切分器的单测保持确定性。基准降到 150ms 后，
     * ±40ms 相当于三成浮动，听起来才不像机器。
     */
    private static int jitteredPause(int baseMs) {
        if (baseMs <= 0) {
            return 0;
        }
        int jitter = java.util.concurrent.ThreadLocalRandom.current().nextInt(81) - 40;
        return Math.max(50, baseMs + jitter);
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {        if (thread == null) {
            return;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 字幕展示用：剥掉完整 marker；尾部疑似未完整 marker 前缀的部分留到下一个 delta 再定。
     *
     * <p>三种标记（cut / pause / join）都不该出现在字幕里；用的是"只剥完整标记"的
     * 版本，因为半截前缀必须保留 —— 否则字幕会少字。
     */
    private String sanitizeDeltaForEmit(String delta) {
        synchronized (sentenceLock) {
            llmEmitBuf.append(delta);
            String s = SentenceSplitter.stripCompleteMarkers(llmEmitBuf.toString());
            int idx = s.lastIndexOf("<|");
            if (idx >= 0 && s.length() - idx < SentenceSplitter.MAX_MARKER_LEN) {
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
        emitState(callState.on(CallStateMachine.Event.FIRST_AUDIO, "tts_first_audio"));
    }

    /**
     * 排空兜底：客户端没有上报 {@code play.drained}（丢包/老客户端）时，
     * 按「已下发字节 ÷ 48 字节/ms」估算播放结束时刻，到点把 SPEAKING 收回 LISTENING。
     *
     * <p>没有这个兜底，一旦 drained 丢失，麦克风会永久关着（用户说什么都没反应），
     * 只能挂断重拨 —— 这是状态机化之前就存在、但被 turnBusy 顺手掩盖的坑。
     */
    private void scheduleDrainEstimate(long epoch) {
        long bytes = turnAudioBytes.get();
        if (bytes <= 0 || firstAudioAtMs == 0L) {
            return;
        }
        long playbackMs = bytes / PCM_BYTES_PER_MS;
        long elapsedMs = System.currentTimeMillis() - firstAudioAtMs;
        // +300ms：留出「最后一帧到达客户端 + 播放器启动」的余量，宁可晚一点放麦克风
        long waitMs = Math.max(0, playbackMs - elapsedMs) + 300;
        log.info("Voice duplex drain estimate armed conv={} playbackMs={} waitMs={}",
                conversationId, playbackMs, waitMs);
        scheduleDrainAfter(epoch, waitMs, "drain_estimate");
    }

    /** 到点若仍是当前回合的 SPEAKING，就收回 LISTENING（客户端 drained 先到则这次是 no-op）。 */
    private void scheduleDrainAfter(long epoch, long waitMs, String reason) {
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed.get() || epoch != turnEpoch.get()) {
                return;
            }
            CallStateMachine.Transition t = callState.on(CallStateMachine.Event.PLAY_DRAINED, reason);
            if (t.changed()) {
                log.info("Voice duplex drain fallback conv={} reason={} waitMs={}",
                        conversationId, reason, waitMs);
                emitState(t);
            }
        }, "voice-drain-" + conversationId);
        timer.setDaemon(true);
        timer.start();
    }

    private void cancelInFlightTurn() {
        var fut = llmFuture.getAndSet(null);
        if (fut != null) {
            fut.cancel(true);
        }
        turnBusy.set(false);
    }

    /**
     * 下发一个预录语气词。
     *
     * <p>只对官方音色（有 pet 固定语音库）发 {@code tts.clip}；自定义/本地音色没有这套音频，
     * 按 {@code fallbackMs} 插等长静音，保证这一句的节奏不变、也绝不阻断。
     */
    private void sendClip(String kind, long epoch) {
        if (!options.clipEnabled() || epoch != turnEpoch.get() || closed.get()) {
            return;
        }
        int fallbackMs = CLIP_FALLBACK_MS.getOrDefault(kind, 600);
        String petId = target.petId();
        if (petId == null || petId.isBlank()) {
            sendAudio(PcmFrameAssembler.silence(fallbackMs), "audio/pcm", epoch);
            return;
        }
        if (audioSent.compareAndSet(false, true)) {
            markFirstAudio();
        }
        long seq = audioSeq.incrementAndGet();
        // 排空估算把 clip 的时长也算进去（服务端没有它的 PCM 字节）
        turnAudioBytes.addAndGet((long) fallbackMs * PCM_BYTES_PER_MS);
        emitJson("tts.clip", n -> {
            n.put("petId", petId);
            n.put("kind", kind);
            n.put("fallbackMs", fallbackMs);
            n.put("seq", seq);
        });
    }

    private void sendAudio(byte[] audio, String mimeHint, long epoch) {
        if (audio == null || audio.length == 0 || !sink.isOpen() || epoch != turnEpoch.get()) {
            return;
        }
        long seq = audioSeq.incrementAndGet();
        turnAudioBytes.addAndGet(audio.length);
        if (options.binaryAudio() && sendAudioBinary(audio, seq)) {
            return;
        }
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("type", "tts.audio");
            n.put("mime", mimeHint == null || mimeHint.isBlank() ? "audio/pcm" : mimeHint);
            n.put("sampleRate", 24000);
            n.put("seq", seq);
            n.put("base64", Base64.getEncoder().encodeToString(audio));
            String frame = objectMapper.writeValueAsString(n);
            synchronized (sendLock) {
                sink.sendText(frame);
            }
        } catch (Exception e) {
            log.debug("send audio failed: {}", e.toString());
        }
    }

    /**
     * 下行二进制音频帧（需要客户端 caps 含 {@code binary.audio}）。
     *
     * <pre>
     *   0-1  'L','Y'   2 version=1   3 type=0x01(PCM16)   4 flags   5 headerLen=16
     *   6-7  reserved  8-11 seq      12-15 sampleRate     16.. s16le payload
     * </pre>
     *
     * @return false 表示 sink 不支持二进制，调用方回退 base64 JSON
     */
    private boolean sendAudioBinary(byte[] audio, long seq) {
        java.nio.ByteBuffer frame = java.nio.ByteBuffer.allocate(16 + audio.length);
        frame.put((byte) 'L').put((byte) 'Y')
                .put((byte) 1)
                .put((byte) 0x01)
                .put((byte) 0)
                .put((byte) 16)
                .put((byte) 0).put((byte) 0)
                .putInt((int) seq)
                .putInt(24000)
                .put(audio);
        synchronized (sendLock) {
            return sink.sendBinary(frame.array());
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

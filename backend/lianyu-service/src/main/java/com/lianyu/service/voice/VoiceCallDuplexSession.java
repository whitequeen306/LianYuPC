package com.lianyu.service.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.service.ai.AsrStreamClient;
import com.lianyu.service.ai.DashScopeTtsRealtimeService;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.dto.AiChatRequest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Voice call duplex: PCM → Zipformer endpoint → LLM token stream → TTS realtime → client.
 */
@Slf4j
public class VoiceCallDuplexSession {

    private final VoiceEventSink sink;
    private final ObjectMapper objectMapper;
    private final long userId;
    private final long conversationId;
    private final VoiceCallService voiceCallService;
    private final DashScopeTtsRealtimeService ttsRealtimeService;
    private final AsrStreamClient.Session asr;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean turnBusy = new AtomicBoolean(false);
    private final AtomicReference<DashScopeTtsRealtimeService.Session> ttsSession = new AtomicReference<>();
    private final AtomicReference<java.util.concurrent.CompletableFuture<?>> llmFuture = new AtomicReference<>();
    private final StringBuilder sentenceBuf = new StringBuilder();
    private volatile String lastPartial = "";
    private final String petId;

    public VoiceCallDuplexSession(
            VoiceEventSink sink,
            ObjectMapper objectMapper,
            long userId,
            long conversationId,
            VoiceCallService voiceCallService,
            AsrStreamClient asrStreamClient,
            DashScopeTtsRealtimeService ttsRealtimeService) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.userId = userId;
        this.conversationId = conversationId;
        this.voiceCallService = voiceCallService;
        this.ttsRealtimeService = ttsRealtimeService;
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

    /** Engine endpoint and client VAD may both fire — take lastPartial once. */
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
        emitJson("asr.final", n -> n.put("text", userText));
        emitJson("turn.start", n -> n.put("userText", userText));

        Thread worker = new Thread(() -> {
            try {
                voiceCallService.persistUserTurn(userId, conversationId, userText);
                AiChatRequest aiRequest = voiceCallService.buildVoiceCallAiRequest(
                        userId, conversationId, userText);
                StringBuilder full = new StringBuilder();
                sentenceBuf.setLength(0);

                DashScopeTtsRealtimeService.Session tts = ttsRealtimeService.startForPet(petId,
                        new DashScopeTtsRealtimeService.AudioListener() {
                            @Override
                            public void onAudio(byte[] pcmOrEncoded, String mimeHint) {
                                sendAudio(pcmOrEncoded, mimeHint);
                            }

                            @Override
                            public void onDone() {
                                emitJson("tts.done", n -> {
                                });
                            }

                            @Override
                            public void onError(String message) {
                                log.warn("Voice duplex TTS error pet={} conv={}: {}",
                                        petId, conversationId, message);
                                emitError("TTS_ERROR", "语音合成失败，请稍后再试");
                            }
                        });
                ttsSession.set(tts);

                var future = voiceCallService.streamVoiceReply(userId, aiRequest, delta -> {
                    if (delta == null || delta.isEmpty() || closed.get()) {
                        return;
                    }
                    full.append(delta);
                    emitJson("llm.delta", n -> n.put("text", delta));
                    feedTts(tts, delta);
                });
                llmFuture.set(future);
                String rawReply = future.join();
                flushTts(tts);
                if (tts != null) {
                    tts.finish();
                }

                String spoken = InnerThoughtFilter.strip(rawReply == null ? "" : rawReply.trim());
                if (spoken.isBlank()) {
                    spoken = "我在听，你再说一遍。";
                }
                spoken = voiceCallService.clampReply(spoken);
                voiceCallService.persistAssistantTurn(userId, conversationId, spoken);
                String reply = spoken;
                emitJson("turn.done", n -> {
                    n.put("userText", userText);
                    n.put("replyText", reply);
                });
            } catch (Exception e) {
                log.warn("Voice duplex turn failed: {}", e.toString());
                emitError("TURN_ERROR", "角色暂时无法回复，请稍后再试");
            } finally {
                DashScopeTtsRealtimeService.Session tts = ttsSession.getAndSet(null);
                if (tts != null) {
                    tts.close();
                }
                llmFuture.set(null);
                turnBusy.set(false);
            }
        }, "voice-call-duplex");
        worker.setDaemon(true);
        worker.start();
    }

    private void feedTts(DashScopeTtsRealtimeService.Session tts, String delta) {
        if (tts == null) {
            return;
        }
        sentenceBuf.append(delta);
        String buf = sentenceBuf.toString();
        int cut = findSentenceCut(buf);
        while (cut > 0) {
            String piece = buf.substring(0, cut).trim();
            buf = buf.substring(cut);
            if (!piece.isBlank()) {
                tts.appendText(piece);
            }
            cut = findSentenceCut(buf);
        }
        sentenceBuf.setLength(0);
        sentenceBuf.append(buf);
        if (sentenceBuf.length() >= 24) {
            String piece = sentenceBuf.toString().trim();
            sentenceBuf.setLength(0);
            if (!piece.isBlank()) {
                tts.appendText(piece);
            }
        }
    }

    private void flushTts(DashScopeTtsRealtimeService.Session tts) {
        if (tts == null) {
            return;
        }
        String rest = sentenceBuf.toString().trim();
        sentenceBuf.setLength(0);
        if (!rest.isBlank()) {
            tts.appendText(rest);
        }
    }

    private static int findSentenceCut(String buf) {
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n' || c == '；') {
                return i + 1;
            }
        }
        return -1;
    }

    private void cancelInFlightTurn() {
        var fut = llmFuture.getAndSet(null);
        if (fut != null) {
            fut.cancel(true);
        }
        DashScopeTtsRealtimeService.Session tts = ttsSession.getAndSet(null);
        if (tts != null) {
            try {
                tts.close();
            } catch (Exception ignored) {
                // ignore
            }
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
            n.put("mime", mimeHint == null ? "audio/pcm" : mimeHint);
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

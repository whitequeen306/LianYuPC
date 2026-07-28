package com.lianyu.service.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.service.ai.AsrService;
import com.lianyu.service.ai.AsrStreamClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Chat mic dictation: Zipformer partial → SenseVoice final on endpoint.
 */
@Slf4j
public class VoiceDictationSession {

    private final VoiceEventSink sink;
    private final ObjectMapper objectMapper;
    private final AsrService asrService;
    private final AsrStreamClient.Session asr;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Single-flight: Zipformer endpoint and client VAD both may fire. */
    private final AtomicBoolean finalizing = new AtomicBoolean(false);
    private final List<byte[]> pcmChunks = new ArrayList<>();
    private final Object pcmLock = new Object();
    private volatile String lastPartial = "";
    private long pcmBytes;

    public VoiceDictationSession(
            VoiceEventSink sink,
            ObjectMapper objectMapper,
            AsrService asrService,
            AsrStreamClient asrStreamClient) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.asrService = asrService;
        this.asr = asrStreamClient.open(new AsrStreamClient.Listener() {
            @Override
            public void onPartial(String text) {
                lastPartial = text == null ? "" : text;
                emit("asr.partial", lastPartial);
            }

            @Override
            public void onFinal(String text) {
                if (text != null && !text.isBlank()) {
                    lastPartial = text;
                    emit("asr.partial", text);
                }
            }

            @Override
            public void onEndpoint() {
                finalizeUtterance();
            }

            @Override
            public void onError(String message) {
                emitError("语音识别失败");
            }

            @Override
            public void onClosed() {
                // upstream closed
            }
        });
    }

    public void onPcm(byte[] pcm) {
        if (closed.get() || pcm == null || pcm.length == 0 || finalizing.get()) {
            return;
        }
        synchronized (pcmLock) {
            if (pcmBytes + pcm.length > 8 * 1024 * 1024) {
                emitError("音频过长");
                return;
            }
            pcmChunks.add(pcm);
            pcmBytes += pcm.length;
        }
        asr.sendPcm(pcm);
    }

    public void clientEndpoint() {
        finalizeUtterance();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        asr.close();
    }

    private void finalizeUtterance() {
        if (closed.get() || !finalizing.compareAndSet(false, true)) {
            return;
        }
        try {
            byte[] pcm;
            String fallbackPartial;
            synchronized (pcmLock) {
                if (pcmChunks.isEmpty()) {
                    return;
                }
                int total = 0;
                for (byte[] c : pcmChunks) {
                    total += c.length;
                }
                pcm = new byte[total];
                int off = 0;
                for (byte[] c : pcmChunks) {
                    System.arraycopy(c, 0, pcm, off, c.length);
                    off += c.length;
                }
                pcmChunks.clear();
                pcmBytes = 0;
                fallbackPartial = lastPartial;
                lastPartial = "";
            }
            // Reset streaming engine so a late client/engine endpoint cannot re-fire
            // on the same utterance while SenseVoice is still running.
            try {
                asr.reset();
            } catch (Exception e) {
                log.debug("ASR reset after endpoint failed: {}", e.toString());
            }
            String finalText;
            try {
                finalText = asrService.transcribePcm(pcm);
            } catch (Exception e) {
                log.warn("SenseVoice final failed, falling back to partial: {}", e.toString());
                finalText = fallbackPartial;
            }
            if (finalText == null) {
                finalText = "";
            }
            finalText = finalText.trim();
            if (!finalText.isBlank()) {
                emit("asr.final", finalText);
            }
        } finally {
            finalizing.set(false);
        }
    }

    private void emit(String type, String text) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("type", type);
            n.put("text", text == null ? "" : text);
            if (sink.isOpen()) {
                sink.sendText(objectMapper.writeValueAsString(n));
            }
        } catch (Exception e) {
            log.debug("dictation emit failed: {}", e.toString());
        }
    }

    private void emitError(String message) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("type", "error");
            n.put("code", "ASR_ERROR");
            n.put("message", message);
            if (sink.isOpen()) {
                sink.sendText(objectMapper.writeValueAsString(n));
            }
        } catch (Exception e) {
            log.debug("dictation error emit failed: {}", e.toString());
        }
    }
}

package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DashScope Qwen3-TTS-VC Realtime WebSocket — stream text in, stream audio out.
 */
@Slf4j
@Service
public class DashScopeTtsRealtimeService {

    public interface AudioListener {
        void onAudio(byte[] pcmOrEncoded, String mimeHint);

        void onDone();

        void onError(String message);

        default void onReady() {
        }
    }

    private final ObjectMapper objectMapper;
    private final PetVoiceRegistry petVoiceRegistry;
    private final HttpClient httpClient;
    private final int connectTimeoutMs;

    @Value("${lianyu.ai.tts.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.ai.tts.api-key:${lianyu.ai.vision.api-key:}}")
    private String apiKey;

    @Value("${lianyu.ai.tts.realtime-ws-url:wss://dashscope.aliyuncs.com/api-ws/v1/realtime}")
    private String realtimeWsUrl;

    @Value("${lianyu.ai.tts.realtime-model:}")
    private String realtimeModelOverride;

    @Value("${lianyu.ai.tts.language-type:Chinese}")
    private String languageType;

    public DashScopeTtsRealtimeService(
            ObjectMapper objectMapper,
            PetVoiceRegistry petVoiceRegistry,
            @Value("${lianyu.ai.tts.realtime-connect-timeout-ms:2500}") int connectTimeoutMs) {
        this.objectMapper = objectMapper;
        this.petVoiceRegistry = petVoiceRegistry;
        // 构造期注入：避免字段 @Value 尚未写入时 HttpClient 用到 0ms
        this.connectTimeoutMs = Math.max(1000, Math.min(connectTimeoutMs, 8000));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.connectTimeoutMs))
                .build();
    }

    public Session startForPet(String petId, AudioListener listener) {
        if (!enabled) {
            listener.onError("TTS disabled");
            return null;
        }
        String voice = petVoiceRegistry.resolveRealtimeVoiceId(petId);
        if (voice == null) {
            listener.onError("no realtime voice mapping");
            return null;
        }
        String key = resolveApiKey();
        if (key == null || key.isBlank()) {
            listener.onError("missing API key");
            return null;
        }
        String model = resolveRealtimeModel();
        String url = realtimeWsUrl.replaceAll("/+$", "") + "?model=" + model;
        Session session = new Session(listener, voice);
        CompletableFuture<WebSocket> connectFuture = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + key)
                .buildAsync(URI.create(url), session);
        try {
            WebSocket ws = connectFuture.get(connectTimeoutMs, TimeUnit.MILLISECONDS);
            session.attach(ws);
            session.sendSessionUpdate();
            return session;
        } catch (InterruptedException e) {
            connectFuture.cancel(true);
            session.close();
            Thread.currentThread().interrupt();
            listener.onError("语音合成连接失败");
            return null;
        } catch (Exception e) {
            connectFuture.cancel(true);
            session.close();
            log.warn("TTS realtime connect failed: {}", e.toString());
            listener.onError("语音合成连接失败");
            return null;
        }
    }

    private String resolveRealtimeModel() {
        if (realtimeModelOverride != null && !realtimeModelOverride.isBlank()) {
            return realtimeModelOverride.trim();
        }
        String fromRegistry = petVoiceRegistry.getRealtimeModel();
        if (fromRegistry != null && !fromRegistry.isBlank()) {
            return fromRegistry;
        }
        return "qwen3-tts-vc-realtime-2026-01-15";
    }

    private String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return System.getenv("DASHSCOPE_API_KEY");
    }

    public final class Session implements WebSocket.Listener {
        private final AudioListener listener;
        private final String voice;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final StringBuilder textBuf = new StringBuilder();
        private final CountDownLatch readyLatch = new CountDownLatch(1);
        private final CountDownLatch finishedLatch = new CountDownLatch(1);
        private volatile WebSocket socket;
        private volatile boolean sessionReady;

        Session(AudioListener listener, String voice) {
            this.listener = listener;
            this.voice = voice;
        }

        void attach(WebSocket socket) {
            this.socket = socket;
        }

        void sendSessionUpdate() {
            ObjectNode session = objectMapper.createObjectNode();
            session.put("voice", voice);
            session.put("language_type", languageType);
            session.put("mode", "commit");
            session.put("response_format", "pcm");
            session.put("sample_rate", 24000);

            ObjectNode root = objectMapper.createObjectNode();
            root.put("event_id", eventId());
            root.put("type", "session.update");
            root.set("session", session);
            sendJson(root);
        }

        private boolean awaitReady() {
            if (sessionReady || closed.get()) {
                return sessionReady && !closed.get();
            }
            try {
                if (!readyLatch.await(Math.max(1000, connectTimeoutMs), TimeUnit.MILLISECONDS)) {
                    log.warn("TTS realtime session.updated timeout voice={}", voice);
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return sessionReady && !closed.get();
        }

        public void appendText(String text) {
            if (text == null || text.isBlank() || closed.get()) {
                return;
            }
            if (!awaitReady()) {
                if (!closed.get()) {
                    log.warn("TTS realtime append skipped — session not ready voice={}", voice);
                    listener.onError("语音合成未就绪");
                    closed.set(true);
                }
                return;
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("event_id", eventId());
            root.put("type", "input_text_buffer.append");
            root.put("text", text);
            sendJson(root);
        }

        public void commit() {
            if (!awaitReady()) {
                return;
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("event_id", eventId());
            root.put("type", "input_text_buffer.commit");
            sendJson(root);
        }

        public void finish() {
            if (!awaitReady()) {
                return;
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("event_id", eventId());
            root.put("type", "session.finish");
            sendJson(root);
        }

        /** Wait for {@code session.finished} after {@link #finish()}. */
        public boolean awaitFinished(long timeoutMs) {
            if (closed.get() && finishedLatch.getCount() == 0) {
                return true;
            }
            try {
                return finishedLatch.await(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        public boolean isReady() {
            return sessionReady && !closed.get();
        }

        public boolean isClosed() {
            return closed.get();
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            readyLatch.countDown();
            finishedLatch.countDown();
            WebSocket ws = socket;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }

        private void sendJson(ObjectNode root) {
            WebSocket ws = socket;
            if (ws == null || closed.get()) {
                return;
            }
            try {
                ws.sendText(objectMapper.writeValueAsString(root), true);
            } catch (Exception e) {
                log.warn("TTS realtime send failed: {}", e.toString());
            }
        }

        private static String eventId() {
            return "evt_" + UUID.randomUUID().toString().replace("-", "");
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                handleEvent(textBuf.toString());
                textBuf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                readyLatch.countDown();
                finishedLatch.countDown();
                listener.onDone();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closed.compareAndSet(false, true)) {
                readyLatch.countDown();
                finishedLatch.countDown();
                log.warn("TTS realtime socket error: {}", error == null ? "null" : error.toString());
                listener.onError(error == null ? "tts error" : String.valueOf(error.getMessage()));
            }
        }

        private void handleEvent(String payload) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String type = root.path("type").asText("");
                switch (type) {
                    case "session.updated" -> {
                        sessionReady = true;
                        readyLatch.countDown();
                        listener.onReady();
                    }
                    case "session.created" -> {
                        // Wait for session.updated after our session.update (voice applied).
                    }
                    case "response.audio.delta", "response.output_audio.delta" -> {
                        String b64 = firstAudioB64(root);
                        if (b64 != null && !b64.isBlank()) {
                            listener.onAudio(Base64.getDecoder().decode(b64), "audio/pcm");
                        }
                    }
                    case "response.audio.done", "response.done" -> listener.onDone();
                    case "session.finished" -> {
                        finishedLatch.countDown();
                        listener.onDone();
                    }
                    case "error" -> {
                        String msg = root.path("error").path("message").asText(
                                root.path("message").asText("tts error"));
                        log.warn("TTS realtime API error: {} payload={}", msg, truncate(payload, 400));
                        listener.onError(msg);
                    }
                    default -> {
                        // ignore other events
                    }
                }
            } catch (Exception e) {
                log.warn("Bad TTS realtime event: {}", e.toString());
            }
        }

        private static String truncate(String s, int max) {
            if (s == null) {
                return "";
            }
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }

        private static String firstAudioB64(JsonNode root) {
            if (root.hasNonNull("delta")) {
                return root.get("delta").asText();
            }
            if (root.hasNonNull("audio")) {
                return root.get("audio").asText();
            }
            JsonNode delta = root.path("delta");
            if (delta.isObject() && delta.hasNonNull("audio")) {
                return delta.get("audio").asText();
            }
            return null;
        }
    }

    /** Synthesize a full phrase via realtime and collect bytes (fallback helper). */
    public byte[] synthesizeCollect(String petId, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        CompletableFuture<byte[]> done = new CompletableFuture<>();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Session session = startForPet(petId, new AudioListener() {
            @Override
            public void onAudio(byte[] pcmOrEncoded, String mimeHint) {
                if (pcmOrEncoded != null) {
                    out.writeBytes(pcmOrEncoded);
                }
            }

            @Override
            public void onDone() {
                done.complete(out.toByteArray());
            }

            @Override
            public void onError(String message) {
                done.completeExceptionally(new IllegalStateException(message));
            }
        });
        if (session == null) {
            return null;
        }
        try {
            session.appendText(text);
            session.commit();
            session.finish();
            byte[] bytes = done.orTimeout(45, java.util.concurrent.TimeUnit.SECONDS).join();
            session.awaitFinished(45_000);
            session.close();
            return bytes;
        } catch (Exception e) {
            session.close();
            log.warn("TTS realtime collect failed: {}", e.toString());
            return null;
        }
    }
}

package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proxies PCM frames to the ASR container WebSocket {@code /stream} (Zipformer online).
 */
@Slf4j
@Component
public class AsrStreamClient {

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onEndpoint();

        void onError(String message);

        void onClosed();
    }

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${lianyu.asr.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${lianyu.asr.stream-connect-timeout-ms:8000}")
    private int connectTimeoutMs;

    public AsrStreamClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, connectTimeoutMs)))
                .build();
    }

    public Session open(Listener listener) {
        String wsUrl = toWsUrl(baseUrl) + "/stream";
        Session session = new Session(listener);
        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), session);
        try {
            session.attach(future.join());
        } catch (Exception e) {
            session.fail("无法连接语音识别流：" + safeMsg(e));
        }
        return session;
    }

    static String toWsUrl(String httpBase) {
        String base = httpBase == null ? "http://localhost:8081" : httpBase.trim().replaceAll("/+$", "");
        if (base.startsWith("https://")) {
            return "wss://" + base.substring("https://".length());
        }
        if (base.startsWith("http://")) {
            return "ws://" + base.substring("http://".length());
        }
        if (base.startsWith("ws://") || base.startsWith("wss://")) {
            return base;
        }
        return "ws://" + base;
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    public final class Session implements WebSocket.Listener {
        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final StringBuilder textBuf = new StringBuilder();
        private volatile WebSocket socket;

        Session(Listener listener) {
            this.listener = listener;
        }

        void attach(WebSocket socket) {
            this.socket = socket;
        }

        public void sendPcm(byte[] pcm) {
            WebSocket ws = socket;
            if (ws == null || closed.get() || pcm == null || pcm.length == 0) {
                return;
            }
            ws.sendBinary(ByteBuffer.wrap(pcm), true);
        }

        public void finish() {
            WebSocket ws = socket;
            if (ws == null || closed.get()) {
                return;
            }
            try {
                ws.sendText("{\"type\":\"finish\"}", true);
            } catch (Exception e) {
                log.debug("ASR stream finish send failed: {}", e.toString());
            }
        }

        public void reset() {
            WebSocket ws = socket;
            if (ws == null || closed.get()) {
                return;
            }
            try {
                ws.sendText("{\"type\":\"reset\"}", true);
            } catch (Exception e) {
                log.debug("ASR stream reset send failed: {}", e.toString());
            }
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            WebSocket ws = socket;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }

        void fail(String message) {
            if (closed.compareAndSet(false, true)) {
                listener.onError(message);
                listener.onClosed();
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                String payload = textBuf.toString();
                textBuf.setLength(0);
                handleEvent(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                listener.onClosed();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error == null ? "asr stream error" : String.valueOf(error.getMessage()));
        }

        private void handleEvent(String payload) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String type = root.path("type").asText("");
                switch (type) {
                    case "partial" -> listener.onPartial(root.path("text").asText("").trim());
                    case "final" -> listener.onFinal(root.path("text").asText("").trim());
                    case "endpoint" -> listener.onEndpoint();
                    case "error" -> listener.onError(root.path("message").asText("asr error"));
                    case "closed" -> {
                        if (closed.compareAndSet(false, true)) {
                            listener.onClosed();
                        }
                    }
                    default -> {
                        // ready / reset ignored
                    }
                }
            } catch (Exception e) {
                log.debug("Bad ASR stream event: {}", e.toString());
            }
        }
    }
}

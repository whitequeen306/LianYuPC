package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

        /**
         * 端点判定成立、但内容是噪声（真实说话不可能流式零字符 + 定稿只有两三个字）。
         * 默认什么都不做；实现方应清掉累积文本、**不要**开回合。
         */
        default void onNoise() {
        }

        void onError(String message);

        void onClosed();
    }

    private final ObjectMapper objectMapper;

    @Value("${lianyu.asr.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${lianyu.asr.stream-connect-timeout-ms:8000}")
    private int connectTimeoutMs;

    private HttpClient httpClient;

    public AsrStreamClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 为什么 HttpClient 不在构造器里建：字段注入（@Value）发生在构造器**之后**，
     * 构造器里读 connectTimeoutMs 只会读到 0，超时实际退化成 1s —— 表现为 ASR 握手偶发被
     * 1s 掐断、重连后识别前几百毫秒空白。放到 @PostConstruct，配置已就绪。
     */
    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout()))
                .build();
    }

    private int connectTimeout() {
        return Math.max(1000, connectTimeoutMs);
    }

    public AsrSession open(Listener listener) {
        String wsUrl = toWsUrl(baseUrl) + "/stream";
        Session session = new Session(listener);
        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), session);
        try {
            // connectTimeout 只管 TCP 建连；对端收了 Upgrade 请求但永远不应答时 join 会永久 park，
            // 给握手未来补硬超时（建连 + 握手余量）
            session.attach(future
                    .orTimeout(connectTimeout() + 2000L, TimeUnit.MILLISECONDS)
                    .join());
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

    public final class Session implements AsrSession, WebSocket.Listener {
        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final StringBuilder textBuf = new StringBuilder();
        private final AtomicLong droppedPcm = new AtomicLong();

        /**
         * 发送串行化。JDK WebSocket 对同一条连接**只允许一个在飞的发送操作**，
         * 并发调用 sendBinary/sendText 会直接抛 IllegalStateException 并可能损坏帧。
         * 上行 PCM 有 50 帧/秒，必须排队或丢弃，不能裸并发。
         */
        private final Object sendLock = new Object();
        private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
        private volatile WebSocket socket;

        Session(Listener listener) {
            this.listener = listener;
        }

        void attach(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public void sendPcm(byte[] pcm) {
            WebSocket ws = socket;
            if (ws == null || closed.get() || pcm == null || pcm.length == 0) {
                return;
            }
            synchronized (sendLock) {
                if (!sendChain.isDone()) {
                    // 上一帧还在飞：丢这一帧。ASR 要的是实时声学流，积压几秒再补发只会
                    // 让识别更晚更错；丢一帧（20ms）几乎无感。
                    droppedPcm.incrementAndGet();
                    return;
                }
                try {
                    sendChain = ws.sendBinary(ByteBuffer.wrap(pcm), true)
                            .handle((r, e) -> {
                                if (e != null) {
                                    log.debug("ASR stream pcm send failed: {}", e.toString());
                                }
                                return null;
                            });
                } catch (IllegalStateException e) {
                    // 连接已被对端/关闭流程收走：丢掉即可，下次 onError/onClose 会通知会话层
                    log.debug("ASR stream pcm send rejected: {}", e.toString());
                }
            }
        }

        @Override
        public void finish() {
            sendControl("{\"type\":\"finish\"}");
        }

        @Override
        public void reset() {
            sendControl("{\"type\":\"reset\"}");
        }

        /**
         * 请求服务端**立即定稿**当前这一句。
         *
         * <p>为什么不能直接 reset：reset 只丢弃、不产出。客户端 VAD 判定"说完了"时，
         * 引擎（rule2 = 1.2s 静音）往往还没到自己端点，此时的结果仍是中间态；
         * reset 掉就等于把这句话的尾巴扔了。commit 让服务端补尾音、解完、做二次解码，
         * 再把定稿文本经 {@code final} 事件回传。
         */
        @Override
        public void commit() {
            sendControl("{\"type\":\"commit\"}");
        }

        /**
         * 控制帧不丢：排在上一次发送之后补发。它们低频（一句一次），排队不会堆积；
         * 丢了会让「定稿/收尾」永久缺失，比延迟严重得多。
         */
        private void sendControl(String json) {
            WebSocket ws = socket;
            if (ws == null || closed.get()) {
                return;
            }
            synchronized (sendLock) {
                sendChain = sendChain.handle((v, e) -> null)
                        .thenCompose(v -> {
                            try {
                                return ws.sendText(json, true);
                            } catch (IllegalStateException e) {
                                log.debug("ASR stream control send rejected: {}", e.toString());
                                return CompletableFuture.completedFuture(ws);
                            }
                        })
                        .handle((v, e) -> {
                            if (e != null) {
                                log.debug("ASR stream control send failed: {}", e.toString());
                            }
                            return null;
                        });
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (sendLock) {
                WebSocket ws = socket;
                if (ws == null) {
                    return;
                }
                // 关闭也要排进发送链：在飞的帧/控制帧先走完，避免 sendClose 撞上单飞约束
                sendChain = sendChain.handle((v, e) -> null)
                        .thenCompose(v -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"))
                        .handle((v, e) -> {
                            if (e != null) {
                                log.debug("ASR stream close failed: {}", e.toString());
                            }
                            return null;
                        });
            }
            long dropped = droppedPcm.get();
            if (dropped > 0) {
                log.info("ASR stream closed droppedPcmFrames={}", dropped);
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
                    // 端点但内容是噪声（回声尾巴）：不要开回合，清掉状态即可
                    case "noise" -> listener.onNoise();
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

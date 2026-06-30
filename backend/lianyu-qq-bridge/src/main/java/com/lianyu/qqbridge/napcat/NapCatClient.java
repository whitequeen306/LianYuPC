package com.lianyu.qqbridge.napcat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lianyu.qqbridge.config.QqBridgeComponent;
import com.lianyu.qqbridge.config.QqBridgeProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NapCat 正向 WebSocket 客户端：一条连接同时接收 OneBot 事件并发送 API 调用。
 * <p>
 * 职责：连接生命周期（指数退避重连 + 心跳判活看门狗）、按 {@code echo} 配对 API 请求/响应、
 * 把 {@code message} 事件以 {@link OneBotMessageEvent} 发布给 Spring（异步消费，避免阻塞 WS 收帧线程）。
 * <p>
 * 仅在 {@code lianyu.qq-bridge.enabled=true} 时装配（见 {@link QqBridgeComponent}）。
 */
@Slf4j
@QqBridgeComponent
@RequiredArgsConstructor
public class NapCatClient {

    private final QqBridgeProperties props;
    private final ApplicationEventPublisher eventPublisher;

    /** OneBot JSON 为 snake_case；用独立 mapper，不影响全局 ObjectMapper。 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .findAndAddModules()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qq-bridge-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger attempt = new AtomicInteger(0);
    private volatile long selfId = 0L;
    private volatile Instant lastFrameAt = Instant.now();
    private ScheduledFuture<?> watchdog;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        log.info("QQ bridge starting, target={}", maskUrl(appendToken(props.getNapcat().getWsUrl(), props.getNapcat().getAccessToken())));
        connect();
        watchdog = scheduler.scheduleAtFixedRate(this::watchdog, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (watchdog != null) {
            watchdog.cancel(false);
        }
        WebSocket ws = socket.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
        failAllPending("bridge shutting down");
        scheduler.shutdownNow();
        log.info("QQ bridge stopped");
    }

    private void connect() {
        String fullUrl = appendToken(props.getNapcat().getWsUrl(), props.getNapcat().getAccessToken());
        try {
            log.info("QQ bridge connecting to {}", maskUrl(fullUrl));
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, props.getNapcat().getConnectTimeoutSeconds())))
                    .buildAsync(URI.create(fullUrl), new FrameListener())
                    .thenAccept(ws -> {
                        socket.set(ws);
                        attempt.set(0);
                        lastFrameAt = Instant.now();
                        log.info("QQ bridge WebSocket connected");
                        onConnect();
                    })
                    .exceptionally(t -> {
                        log.warn("QQ bridge connect failed: {}", String.valueOf(t));
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            log.warn("QQ bridge connect error: {}", String.valueOf(e));
            scheduleReconnect();
        }
    }

    private void onConnect() {
        // 探测自身 QQ 号，用于群聊识别 @
        sendApi("get_login_info", Map.of())
                .whenComplete((data, err) -> {
                    if (err != null) {
                        log.warn("QQ bridge get_login_info failed: {}", String.valueOf(err));
                        return;
                    }
                    try {
                        long uid = data.path("user_id").asLong(0L);
                        if (uid > 0) {
                            selfId = uid;
                            log.info("QQ bridge logged in as {}", uid);
                        }
                    } catch (Exception e) {
                        log.warn("QQ bridge get_login_info parse failed: {}", String.valueOf(e));
                    }
                });
    }

    private void onDisconnect() {
        socket.set(null);
        failAllPending("connection closed");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        int n = attempt.incrementAndGet();
        long base = Math.min(30_000L, 1_000L * (1L << Math.min(n, 5)));
        long delay = base + ThreadLocalRandom.current().nextLong(0, 1_000);
        scheduler.schedule(() -> {
            reconnecting.set(false);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void watchdog() {
        try {
            if (!running.get() || socket.get() == null) {
                return;
            }
            long since = Duration.between(lastFrameAt, Instant.now()).getSeconds();
            int timeout = Math.max(30, props.getNapcat().getHeartbeatTimeoutSeconds());
            if (since > timeout) {
                log.warn("QQ bridge no frame for {}s (>{}s), force reconnect", since, timeout);
                WebSocket ws = socket.getAndSet(null);
                if (ws != null) {
                    try {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "watchdog");
                    } catch (Exception ignored) {
                        // 忽略关闭异常
                    }
                }
                failAllPending("watchdog reconnect");
                scheduleReconnect();
            }
        } catch (Exception e) {
            log.warn("QQ bridge watchdog error: {}", String.valueOf(e));
        }
    }

    private void handleFrame(String json) {
        lastFrameAt = Instant.now();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode echoNode = root.get("echo");
            if (echoNode != null && !echoNode.isNull()) {
                String echo = echoNode.asText();
                CompletableFuture<JsonNode> future = pending.remove(echo);
                if (future != null) {
                    JsonNode data = root.get("data");
                    if (data == null || data.isNull()) {
                        data = mapper.getNodeFactory().objectNode();
                    }
                    int retcode = root.path("retcode").asInt(0);
                    if (retcode != 0) {
                        future.completeExceptionally(new IllegalStateException(
                                "OneBot API retcode=" + retcode + " status=" + root.path("status").asText()));
                    } else {
                        future.complete(data);
                    }
                } else {
                    log.debug("QQ bridge received API response with unknown echo: {}", echo);
                }
                return;
            }
            String postType = root.path("post_type").asText("");
            if ("message".equals(postType)) {
                OneBotModels.MessageEvent ev = mapper.treeToValue(root, OneBotModels.MessageEvent.class);
                eventPublisher.publishEvent(new OneBotMessageEvent(this, ev));
            } else if ("meta_event".equals(postType)) {
                // heartbeat / lifecycle：仅作活性判据（lastFrameAt 已更新），无需处理
            } else {
                log.debug("QQ bridge ignored OneBot event post_type={}", postType);
            }
        } catch (Exception e) {
            log.warn("QQ bridge failed to handle frame: {} | frame={}", String.valueOf(e), abbreviate(json, 500));
        }
    }

    /**
     * 发送一次 OneBot API 调用，返回其 {@code data} 字段的 Future（按 {@code echo} 配对，30s 超时）。
     */
    public CompletableFuture<JsonNode> sendApi(String action, Map<String, Object> params) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        WebSocket ws = socket.get();
        if (ws == null) {
            future.completeExceptionally(new IllegalStateException("QQ bridge not connected"));
            return future;
        }
        String echo = UUID.randomUUID().toString();
        pending.put(echo, future);
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                    "action", action,
                    "params", params == null ? Map.of() : params,
                    "echo", echo));
        } catch (Exception e) {
            pending.remove(echo);
            future.completeExceptionally(e);
            return future;
        }
        try {
            ws.sendText(payload, true);
        } catch (Exception e) {
            pending.remove(echo);
            future.completeExceptionally(e);
            return future;
        }
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> f = pending.remove(echo);
            if (f != null) {
                f.completeExceptionally(new TimeoutException("QQ API timeout: " + action));
            }
        }, 30, TimeUnit.SECONDS);
        return future;
    }

    /** 发送私聊文本（fire-and-forget，失败仅告警）。 */
    public void sendPrivateMsg(long userId, String text) {
        sendApi("send_private_msg", Map.of(
                        "user_id", userId,
                        "message", List.of(Map.of("type", "text", "data", Map.of("text", text)))))
                .whenComplete((d, e) -> {
                    if (e != null) {
                        log.warn("QQ bridge send_private_msg failed for {}: {}", userId, String.valueOf(e));
                    }
                });
    }

    /** 发送群聊文本（fire-and-forget，失败仅告警）。 */
    public void sendGroupMsg(long groupId, String text) {
        sendApi("send_group_msg", Map.of(
                        "group_id", groupId,
                        "message", List.of(Map.of("type", "text", "data", Map.of("text", text)))))
                .whenComplete((d, e) -> {
                    if (e != null) {
                        log.warn("QQ bridge send_group_msg failed for {}: {}", groupId, String.valueOf(e));
                    }
                });
    }

    /** 当前登录的机器人 QQ 号（get_login_info 探测结果，未探测到为 0）。 */
    public long getSelfId() {
        return selfId;
    }

    private void failAllPending(String reason) {
        if (pending.isEmpty()) {
            return;
        }
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(reason)));
        pending.clear();
    }

    private static String appendToken(String url, String token) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (token == null || token.isBlank()) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        // token 进入 query 须 URL 编码：未编码的 & # = 空格等会注入或截断 query 参数，
        // 既可能让 NapCat 解析出错的 access_token，也可能拼出非预期键值
        return url + sep + "access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static String maskUrl(String url) {
        return url == null ? null : url.replaceAll("access_token=[^&]*", "access_token=***");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + ")";
    }

    /**
     * OneBot WS 帧监听器：文本帧可能分多片到达，按 {@code last} 拼接后整体处理。
     * 所有异常路径都触发重连，绝不让异常冒泡到 JDK WS 框架导致静默断连。
     */
    private final class FrameListener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String frame = buf.toString();
                buf.setLength(0);
                handleFrame(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("QQ bridge WebSocket closed: {} {}", statusCode, reason);
            onDisconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("QQ bridge WebSocket error: {}", String.valueOf(error));
            onDisconnect();
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            try {
                webSocket.sendPong(message);
            } catch (Exception ignored) {
                // 忽略回 pong 异常
            }
            return null;
        }
    }

}

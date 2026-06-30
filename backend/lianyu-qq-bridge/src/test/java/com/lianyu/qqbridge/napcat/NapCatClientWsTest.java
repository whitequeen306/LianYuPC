package com.lianyu.qqbridge.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.qqbridge.config.QqBridgeProperties;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * NapCatClient 真机级集成测试：本地起一个 WebSocketServer 模拟 NapCat 正向 WS。
 * 覆盖：握手连入 + get_login_info 的 echo 配对（selfId 探测）、message 事件发布、
 * sendApi 请求/响应 echo 配对，以及对端关闭连接后的指数退避重连。
 * <p>
 * 用 Awaitility 等待异步条件，避免 Thread.sleep 抖动；重连首退避约 2~3s，留 15s 余量。
 * 模拟服务器线程设为守护线程，避免测试 JVM 残留挂起。
 */
class NapCatClientWsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BOT_ID = 99999L;

    private MockNapCatServer server;
    private NapCatClient client;
    private final List<Object> published = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockNapCatServer(new InetSocketAddress("127.0.0.1", freePort()));
        server.setDaemon(true);
        server.start();
        server.awaitStarted();

        QqBridgeProperties props = new QqBridgeProperties();
        props.getNapcat().setWsUrl("ws://127.0.0.1:" + server.getPort() + "/");
        props.getNapcat().setAccessToken("");
        props.getNapcat().setConnectTimeoutSeconds(5);
        props.getNapcat().setHeartbeatTimeoutSeconds(3600); // 测试期间不让看门狗误判重连

        ApplicationEventPublisher publisher = published::add;
        client = new NapCatClient(props, publisher);
        client.start();
        // 稳定的“已连入”判据：get_login_info 往返成功 → selfId 已探测
        await().atMost(Duration.ofSeconds(10)).until(() -> client.getSelfId() == BOT_ID);
    }

    @AfterEach
    void tearDown() {
        try {
            if (client != null) {
                client.stop();
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
        try {
            if (server != null) {
                server.stop(500);
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }

    @Test
    void messageFrame_publishedAsOneBotMessageEvent() {
        String privateMsg = "{\"post_type\":\"message\",\"message_type\":\"private\",\"sub_type\":\"friend\","
                + "\"message_id\":1,\"user_id\":12345,\"self_id\":99999,"
                + "\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"你好\"}}],"
                + "\"raw_message\":\"你好\",\"sender\":{\"user_id\":12345,\"nickname\":\"tester\",\"card\":\"\"},\"time\":0}";
        server.pushToClients(privateMsg);

        await().atMost(Duration.ofSeconds(5)).until(() -> !published.isEmpty());
        OneBotMessageEvent ev = (OneBotMessageEvent) published.get(0);
        assertThat(ev.getMessage().messageType()).isEqualTo("private");
        assertThat(ev.getMessage().userId()).isEqualTo(12345L);
        assertThat(ev.getMessage().selfId()).isEqualTo(BOT_ID);
        assertThat(ev.getMessage().rawMessage()).isEqualTo("你好");
    }

    @Test
    void sendApi_echoPairing_completesWithData() throws Exception {
        CompletableFuture<JsonNode> fut = client.sendApi("send_private_msg", Map.of(
                "user_id", 123L,
                "message", List.of(Map.of("type", "text", "data", Map.of("text", "hi")))));

        JsonNode data = fut.get(5, TimeUnit.SECONDS);
        assertThat(data.path("message_id").asLong()).isEqualTo(7L);
        assertThat(server.received.stream().anyMatch(s -> s.contains("\"send_private_msg\""))).isTrue();
    }

    @Test
    void serverClosesConnection_clientReconnects() {
        await().atMost(Duration.ofSeconds(5)).until(() -> !server.getConnections().isEmpty());
        assertThat(server.openCount.get()).isEqualTo(1);
        // 对端主动关闭 → 客户端 onClose → 指数退避重连（首退避约 2~3s）
        server.getConnections().iterator().next().close();
        await().atMost(Duration.ofSeconds(15)).until(() -> server.openCount.get() >= 2);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * 模拟 NapCat 正向 WS：收到带 echo 的 API 调用就原样回配对响应（get_login_info 回 BOT_ID，
     * 其余回 message_id=7），以便驱动客户端 echo 配对与 selfId 探测。
     */
    private static final class MockNapCatServer extends WebSocketServer {
        final List<String> received = new CopyOnWriteArrayList<>();
        final AtomicInteger openCount = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);

        MockNapCatServer(InetSocketAddress addr) {
            super(addr);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            openCount.incrementAndGet();
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            // no-op
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            received.add(message);
            try {
                JsonNode node = MAPPER.readTree(message);
                JsonNode echo = node.get("echo");
                if (echo == null || echo.isNull()) {
                    return;
                }
                ObjectNode data = MAPPER.createObjectNode();
                if ("get_login_info".equals(node.path("action").asText(""))) {
                    data.put("user_id", BOT_ID);
                    data.put("nickname", "mockbot");
                } else {
                    data.put("message_id", 7);
                }
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("status", "ok");
                resp.put("retcode", 0);
                resp.set("data", data);
                resp.put("echo", echo.asText());
                conn.send(MAPPER.writeValueAsString(resp));
            } catch (Exception ignored) {
                // 解析失败的帧忽略，不影响其它用例
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            // no-op
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        void awaitStarted() throws InterruptedException {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("mock NapCat WS server did not start");
            }
        }

        void pushToClients(String json) {
            broadcast(json);
        }
    }
}

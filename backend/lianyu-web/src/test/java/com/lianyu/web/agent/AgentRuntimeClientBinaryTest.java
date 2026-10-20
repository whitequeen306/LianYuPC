package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentRuntimeClient#postBytes} 的真实 HTTP 契约。
 *
 * <p>为什么必须打真端口而不是 mock：这里真正要证明的是**编解码上限**。
 * Spring 的 {@code ByteArrayDecoder} 默认 {@code maxInMemorySize = 256KB}，
 * 十秒语音就 300KB+ —— 用默认配置会在**正常业务**上抛
 * {@code DataBufferLimitException}。mock 掉 client 就完全测不到这一层，
 * 而这正是「测试全绿、线上没声音」的典型形态。
 */
class AgentRuntimeClientBinaryTest {

    /** 400KB：明显超过 256KB 默认上限，又远小于我们配的 16MB。 */
    private static final byte[] BIG_AUDIO = buildAudio(400 * 1024);

    private static HttpServer server;
    private static AgentRuntimeClient client;

    private static byte[] buildAudio(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        return bytes;
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange ->
                respond(exchange, 200, "audio/wav", BIG_AUDIO));
        server.createContext("/empty", exchange -> respond(exchange, 204, null, new byte[0]));
        server.createContext("/boom", exchange ->
                respond(exchange, 500, "text/plain", "oops".getBytes(StandardCharsets.UTF_8)));
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AgentRuntimeClient(new AgentRuntimeProperties(baseUrl), new ObjectMapper());
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        // 必须读完请求体，否则连接可能被对端重置
        exchange.getRequestBody().readAllBytes();
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    @Test
    void postBytesReturnsLargeAudioDespiteSpringDefaultLimit() {
        AgentRuntimeClient.BinaryResponse response =
                client.postBytes("/audio", 0L, Map.of("text", "hi"));
        assertNotNull(response, "400KB 音频不能因为编解码上限被丢掉");
        assertEquals(BIG_AUDIO.length, response.bytes().length);
        assertEquals("audio/wav", response.contentType());
        assertEquals('R', response.bytes()[0]);
    }

    @Test
    void postBytesReturnsNullOn204() {
        assertNull(client.postBytes("/empty", 0L, Map.of()), "204 表示没有音频，必须是 null 而不是空数组");
    }

    @Test
    void postBytesReturnsNullOnServerError() {
        assertNull(client.postBytes("/boom", 0L, Map.of()));
    }

    @Test
    void postBytesReturnsNullOnTransportFailure() throws IOException {
        // 取一个「刚刚空出来」的端口：绑上再关，保证没人监听但不影响别的用例
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AgentRuntimeClient deadClient = new AgentRuntimeClient(
                new AgentRuntimeProperties("http://127.0.0.1:" + closedPort), new ObjectMapper());
        assertNull(deadClient.postBytes("/audio", 0L, Map.of()));
    }
}

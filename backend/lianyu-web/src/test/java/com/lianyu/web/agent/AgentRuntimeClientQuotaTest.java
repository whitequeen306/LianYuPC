package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.agent.ModelQuotaGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 主动关怀的额度记账契约：说出来了才算"额度能用"，空内容按失败计一次 strike。
 *
 * <p>为什么必须有这一组：线上 userId=398 的 provider 4xx 被上游吞成空串后，
 * 客户端只看到 200 + 空文本，于是既不解熔断也不记失败，调度器每 30 秒继续烧一轮
 * 2–4k 输入 token，一天 378 次。空内容必须落到 strike 档才能收敛。
 */
class AgentRuntimeClientQuotaTest {

    private static HttpServer server;
    private static ModelQuotaGuard guard;
    private static AgentRuntimeClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/proactive-turn", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // 请求里带 "blank" 的场景返回空内容，其余返回一句正常台词
            String reply = body.contains("\"blank\"")
                    ? "{\"assistantRaw\":\"\",\"totalTokens\":3}"
                    : "{\"assistantRaw\":\"在忙吗？\",\"totalTokens\":42}";
            byte[] payload = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        guard = Mockito.mock(ModelQuotaGuard.class);
        Mockito.when(guard.allow(ArgumentMatchers.anyLong())).thenReturn(true);
        AgentToolCatalog catalog = Mockito.mock(AgentToolCatalog.class);
        Mockito.when(catalog.chatTools()).thenReturn(List.of("get_current_time"));
        client = new AgentRuntimeClient(new AgentRuntimeProperties(baseUrl), new ObjectMapper(), catalog, guard);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String turn(long userId, String scene) {
        return client.proactiveTurn(userId, scene, "persona", "task",
                List.of(), "http://text", "key", "model", "deepseek", "zh", 7L, List.of());
    }

    @Test
    void blankContentCountsAsFailureSoTheLoopStops() {
        String text = turn(398L, "blank");

        org.junit.jupiter.api.Assertions.assertEquals("", text == null ? "" : text.trim());
        Mockito.verify(guard).recordFailure(398L, AgentRuntimeClient.BLANK_CONTENT_CODE,
                "proactive turn returned blank");
        Mockito.verify(guard, Mockito.never()).recordSuccess(398L);
    }

    @Test
    void realContentClearsTheBreakerImmediately() {
        String text = turn(399L, "PROACTIVE");

        org.junit.jupiter.api.Assertions.assertEquals("在忙吗？", text);
        Mockito.verify(guard).recordSuccess(399L);
        Mockito.verify(guard, Mockito.never()).recordFailure(ArgumentMatchers.eq(399L),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    void blockedUserNeverReachesTheNetwork() {
        Mockito.when(guard.allow(400L)).thenReturn(false);

        String text = turn(400L, "PROACTIVE");

        org.junit.jupiter.api.Assertions.assertNull(text);
        Mockito.verify(guard, Mockito.never()).recordSuccess(400L);
        Mockito.verify(guard, Mockito.never()).recordFailure(ArgumentMatchers.eq(400L),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }
}

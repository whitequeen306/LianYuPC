package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.SseChatStreamHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 流式桥接测试。
 *
 * <p>用可控的假 emitter 断言「发给客户端什么」与「收尾顺序」——
 * 这两件事错了就是用户可见故障（缺字、只剩第一段、不结束）。
 */
class AgentStreamBridgeTest {

    /** 记录下发内容的假 emitter（只覆盖测试用到的能力）。 */
    static class FakeEmitter extends SseEmitter {
        final List<String> dataFrames = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        boolean completed;
        boolean failOnSend;

        FakeEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("client gone");
            }
            for (var datum : builder.build()) {
                Object value = datum.getData();
                if (value instanceof String s) {
                    if (s.startsWith(":")) {
                        comments.add(s);
                    } else {
                        dataFrames.add(s);
                    }
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    static class RecordingCallback implements com.lianyu.service.agent.AgentStreamCallback {
        String completedContent;
        Throwable completedError;
        int beforeCompleteCalls;
        String beforeCompleteContent;

        @Override
        public void onComplete(String fullContent, Throwable error) {
            completedContent = fullContent;
            completedError = error;
        }

        @Override
        public void beforeStreamComplete(SseEmitter emitter, String fullContent) {
            beforeCompleteCalls++;
            beforeCompleteContent = fullContent;
        }
    }

    private AgentStreamBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new AgentStreamBridge(new SseChatStreamHelper(new ObjectMapper()));
    }

    private static long countOf(FakeEmitter emitter, String needle) {
        return emitter.dataFrames.stream().filter(f -> f.contains(needle)).count();
    }

    private static String content(String text) {
        return "data: {\"content\": \"" + text + "\"}";
    }

    @Test
    void relaysContentIncrementallyThenFinishesWithDone() {
        FakeEmitter emitter = new FakeEmitter();
        RecordingCallback callback = new RecordingCallback();
        bridge.consume(
                List.of(": connected", content("你"), "", content("好"), "data: [DONE]"),
                emitter, callback);

        long contentFrames = emitter.dataFrames.stream().filter(f -> f.contains("\"content\"")).count();
        assertEquals(2, contentFrames, "增量帧必须逐条下发（不是攒完再发）");
        assertEquals(1, countOf(emitter, "[DONE]"), "终止帧只发一次");
        assertTrue(emitter.completed);
        assertEquals("你好", callback.completedContent);
        assertNull(callback.completedError);
        assertEquals(1, callback.beforeCompleteCalls, "pieces 生成钩子必须被调用一次");
        assertEquals("你好", callback.beforeCompleteContent);
    }

    @Test
    void replaceOverridesAccumulatedContent() {
        FakeEmitter emitter = new FakeEmitter();
        RecordingCallback callback = new RecordingCallback();
        bridge.consume(
                List.of(content("I miss"), content(" you"), "data: {\"replace\": \"今晚想你\"}", "data: [DONE]"),
                emitter, callback);

        // replace 帧要转发给客户端（前端据此整体替换）
        assertTrue(emitter.dataFrames.stream().anyMatch(f -> f.contains("replace")));
        assertEquals("今晚想你", callback.completedContent, "最终内容应为替换后的全文");
    }

    @Test
    void errorFrameFinishesWithErrorAndPartialContent() {
        FakeEmitter emitter = new FakeEmitter();
        RecordingCallback callback = new RecordingCallback();
        bridge.consume(
                List.of(content("说到一半"), "data: {\"error\": \"上游中断\"}"),
                emitter, callback);

        assertTrue(emitter.dataFrames.stream().anyMatch(f -> f.contains("error")));
        assertNotNull(callback.completedError);
        assertEquals("说到一半", callback.completedContent, "已下发内容必须回传给落库");
    }

    @Test
    void emptyContentFinishesWithRetryHint() {
        FakeEmitter emitter = new FakeEmitter();
        RecordingCallback callback = new RecordingCallback();
        bridge.consume(List.of("data: [DONE]"), emitter, callback);

        assertTrue(emitter.dataFrames.stream().anyMatch(f -> f.contains("对方没有回复内容")));
        assertNotNull(callback.completedError);
    }

    @Test
    void ignoresUnknownFramesAndPieces() {
        FakeEmitter emitter = new FakeEmitter();
        RecordingCallback callback = new RecordingCallback();
        bridge.consume(
                List.of("event: message", "data: boom", "data: {\"pieces\": [\"a\"]}", content("x"), "data: [DONE]"),
                emitter, callback);

        // pieces 不能由本层下发（切分是 Java 的职责）
        assertFalse(emitter.dataFrames.stream().anyMatch(f -> f.contains("pieces")));
        assertEquals("x", callback.completedContent);
    }

    @Test
    void clientDisconnectStopsRelayingAndCompletes() {
        FakeEmitter emitter = new FakeEmitter();
        emitter.failOnSend = true;
        RecordingCallback callback = new RecordingCallback();

        bridge.consume(List.of(content("a"), content("b"), "data: [DONE]"), emitter, callback);

        assertTrue(emitter.completed, "断开后必须 complete，避免连接悬挂");
        assertNull(callback.completedError, "客户端断开不算业务失败");
    }

    @Test
    void failReportsErrorMessage() {
        FakeEmitter emitter = new FakeEmitter();
        RecordingCallback callback = new RecordingCallback();
        AgentStreamBridge.Relay relay = bridge.newRelay(emitter, callback);
        relay.accept(content("半句"));
        relay.fail("连接对方模型失败，请稍后再试");

        assertTrue(emitter.dataFrames.stream().anyMatch(f -> f.contains("连接对方模型失败")));
        assertNotNull(callback.completedError);
        assertEquals("半句", callback.completedContent);
    }

    @Test
    void finishIsIdempotent() {
        FakeEmitter emitter = new FakeEmitter();
        RecordingCallback callback = new RecordingCallback();
        AgentStreamBridge.Relay relay = bridge.newRelay(emitter, callback);
        relay.accept(content("hi"));
        relay.finish();
        relay.finish();  // 二次调用不得重复发 [DONE]
        relay.fail("late");  // 收尾后 fail 也应被忽略

        assertEquals(1, countOf(emitter, "[DONE]"));
        assertNull(callback.completedError);
    }
}

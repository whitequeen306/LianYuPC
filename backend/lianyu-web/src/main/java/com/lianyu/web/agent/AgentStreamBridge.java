package com.lianyu.web.agent;

import com.lianyu.service.ai.SseChatStreamHelper;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把 Python `/agent/chat-stream` 的 SSE 桥接到 Java 的 SSE 下发链路。
 *
 * <p>用法：`newRelay(...)` 拿到增量中继，逐行 `accept(line)`，流结束 `finish()`。
 * 增量转发保证首字延迟与 Java 原链路一致（不是攒完再发）。
 *
 * <p><b>关键边界</b>：Python 的 `[DONE]` **不转发**。多气泡切分（pieces）由 Java
 * `AssistantReplySplitter` 在 `beforeStreamComplete` 生成，`[DONE]` 必须由
 * `SseChatStreamHelper` 在切分之后发 —— 提前透传会让客户端在 pieces 前断流，
 * 表现为「回复只剩第一段」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStreamBridge {

    private final SseChatStreamHelper sseHelper;

    /** 增量中继：一次流式对话一个实例（非线程安全，按流独占）。 */
    public Relay newRelay(SseEmitter emitter, com.lianyu.service.agent.AgentStreamCallback callback) {
        return new Relay(emitter, callback);
    }

    /** 便捷入口（测试/离线回放用）：一次性喂完整行序列并收尾。 */
    public void consume(List<String> lines, SseEmitter emitter, com.lianyu.service.agent.AgentStreamCallback callback) {
        Relay relay = newRelay(emitter, callback);
        for (String line : lines) {
            if (!relay.accept(line)) {
                return;  // 客户端已断开，中继内部已收尾
            }
        }
        relay.finish();
    }

    public class Relay {

        private final SseEmitter emitter;
        private final com.lianyu.service.agent.AgentStreamCallback callback;
        private final StringBuilder content = new StringBuilder();
        private String lastReplacement;
        private String errorMessage;
        private boolean closed;

        Relay(SseEmitter emitter, com.lianyu.service.agent.AgentStreamCallback callback) {
            this.emitter = emitter;
            this.callback = callback;
        }

        /**
         * 消费一行。
         *
         * @return false 表示客户端已断开、应停止消费上游（避免继续计费）
         */
        public boolean accept(String line) {
            if (closed) {
                return false;
            }
            AgentSseParser.Event event = AgentSseParser.parseLine(line);
            switch (event.type()) {
                case CONTENT -> {
                    try {
                        sseHelper.sendSseChunk(emitter, event.text());
                        content.append(event.text());
                    } catch (IOException e) {
                        log.info("Agent stream client disconnected, stop relaying: {}", e.getMessage());
                        abort();
                        return false;
                    }
                }
                case REPLACE -> {
                    try {
                        sseHelper.sendSseReplace(emitter, event.text());
                    } catch (IOException e) {
                        log.info("Agent stream client disconnected on replace: {}", e.getMessage());
                        abort();
                        return false;
                    }
                    lastReplacement = event.text();
                }
                case ERROR -> errorMessage = event.text();
                case DONE, IGNORED -> {
                    // 终止与心跳都不向客户端透传
                }
            }
            return true;
        }

        /** 流正常结束（上游 [DONE] 或连接关闭）时的收尾。 */
        public void finish() {
            if (closed) {
                return;
            }
            closed = true;
            String fullContent = lastReplacement != null ? lastReplacement : content.toString();
            if (errorMessage != null) {
                sseHelper.finishSseError(emitter, errorMessage, fullContent, callback);
                return;
            }
            if (fullContent.isBlank()) {
                sseHelper.finishSseError(emitter, "对方没有回复内容，请重试", fullContent, callback);
                return;
            }
            sseHelper.finishSseSuccess(emitter, fullContent, callback);
        }

        /** 上游异常（连不上/超时）时的收尾。 */
        public void fail(String message) {
            if (closed) {
                return;
            }
            closed = true;
            String partial = lastReplacement != null ? lastReplacement : content.toString();
            sseHelper.finishSseError(emitter, message, partial, callback);
        }

        private void abort() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }
            if (callback != null) {
                String partial = lastReplacement != null ? lastReplacement : content.toString();
                callback.onComplete(partial, null);
            }
        }
    }
}

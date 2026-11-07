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
        return new Relay(emitter, callback, null);
    }

    /**
     * 带"成功回调"的中继：只有整条流**说完且没有 error 帧**才触发。
     *
     * <p>用来把"额度已恢复"这件事接到正常聊天上：用户充值后随手聊一句成功，
     * 熔断键当场清掉，主动关怀下一轮扫描就恢复——不必等半开探测（最长 6 小时）。
     */
    public Relay newRelay(SseEmitter emitter,
                          com.lianyu.service.agent.AgentStreamCallback callback,
                          Runnable onSuccess) {
        return new Relay(emitter, callback, onSuccess);
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
        /** 整条流成功收尾后的回调（额度恢复用）；可为 null。 */
        private final Runnable onSuccess;
        private final StringBuilder content = new StringBuilder();
        private String lastReplacement;
        private String errorMessage;
        private boolean closed;

        Relay(SseEmitter emitter, com.lianyu.service.agent.AgentStreamCallback callback, Runnable onSuccess) {
            this.emitter = emitter;
            this.callback = callback;
            this.onSuccess = onSuccess;
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
            notifySuccess();
        }

        /** 成功回调只跑一次，且任何异常都不能影响已经发完的回复。 */
        private void notifySuccess() {
            if (onSuccess == null) {
                return;
            }
            try {
                onSuccess.run();
            } catch (Exception e) {
                log.warn("Agent stream success hook failed: {}", e.toString());
            }
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

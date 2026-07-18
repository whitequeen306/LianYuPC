package com.lianyu.ai.graph;

/**
 * Graph 流式调用时由外层注入的 token 消费者（例如桥接到 SseEmitter）。
 * 不持有传输层对象，避免 Graph 与 SSE 生命周期耦合。
 */
@FunctionalInterface
public interface StreamTokenConsumer {

    void onToken(String delta) throws Exception;
}

package com.lianyu.service.ai;

/**
 * 一条到 ASR 容器的流式会话。
 *
 * <p>抽成接口是为了让会话层（{@code VoiceCallDuplexSession}）的单测可以注入假实现，
 * 不必真的建 WebSocket；同时把「实现细节（JDK WebSocket）」与「会话层依赖」隔开。
 */
public interface AsrSession extends AutoCloseable {

    /** 送入一帧 16kHz / s16le / mono PCM。 */
    void sendPcm(byte[] pcm);

    /** 丢弃当前句的声学上下文（谨慎使用：服务端端点后已经自己 reset 过）。 */
    void reset();

    /** 请求服务端立即定稿当前这一句（客户端 VAD 触发）。 */
    void commit();

    /** 收尾：补尾音并结束流。 */
    void finish();

    @Override
    void close();
}

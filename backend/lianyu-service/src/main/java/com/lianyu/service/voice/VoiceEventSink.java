package com.lianyu.service.voice;

/** Outbound channel from duplex sessions to the client WebSocket. */
@FunctionalInterface
public interface VoiceEventSink {
    void sendText(String json);

    /**
     * 下行二进制帧（PCM）。默认不支持 —— 调用方会回退 base64 JSON，
     * 老客户端/老实现的协议面完全不变。
     *
     * @return true 表示已按二进制发出；false 表示不支持，调用方必须走文本兜底
     */
    default boolean sendBinary(byte[] payload) {
        return false;
    }

    default boolean isOpen() {
        return true;
    }
}

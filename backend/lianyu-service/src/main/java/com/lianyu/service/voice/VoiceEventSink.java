package com.lianyu.service.voice;

/** Outbound channel from duplex sessions to the client WebSocket. */
@FunctionalInterface
public interface VoiceEventSink {
    void sendText(String json);

    default boolean isOpen() {
        return true;
    }
}

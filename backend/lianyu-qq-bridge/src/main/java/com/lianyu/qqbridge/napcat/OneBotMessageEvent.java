package com.lianyu.qqbridge.napcat;

import org.springframework.context.ApplicationEvent;

/**
 * NapCat 收到 OneBot {@code message} 事件时发布，由 {@code QqBridgeTurnHandler} 异步消费。
 * 用 Spring 事件解耦：NapCatClient 不依赖处理器，避免与处理器（需要回发消息）形成构造环。
 */
public class OneBotMessageEvent extends ApplicationEvent {

    private final OneBotModels.MessageEvent message;

    public OneBotMessageEvent(Object source, OneBotModels.MessageEvent message) {
        super(source);
        this.message = message;
    }

    public OneBotModels.MessageEvent getMessage() {
        return message;
    }
}

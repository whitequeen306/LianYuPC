package com.lianyu.service.dto;

import java.io.Serializable;

public record PushNotifyTask(
        Long userId,
        String title,
        String body,
        Long conversationId
) implements Serializable {
}

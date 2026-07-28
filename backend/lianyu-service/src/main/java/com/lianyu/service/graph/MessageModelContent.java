package com.lianyu.service.graph;

import com.lianyu.dao.entity.Message;

/**
 * Resolves which text a stored message should contribute to the model context.
 */
public final class MessageModelContent {

    private MessageModelContent() {
    }

    public static String forModel(Message msg) {
        if (msg == null) {
            return "";
        }
        String context = msg.getContextContent();
        if (context != null && !context.isBlank()) {
            return context.trim();
        }
        return msg.getContent() == null ? "" : msg.getContent();
    }
}

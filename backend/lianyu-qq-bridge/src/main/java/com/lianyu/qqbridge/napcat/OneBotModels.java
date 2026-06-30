package com.lianyu.qqbridge.napcat;

import java.util.List;
import java.util.Map;

/**
 * OneBot 11 上报事件与消息段的精简模型，仅覆盖 QQ 桥所需字段。
 * <p>
 * 字段以 camelCase 声明，由 {@link NapCatClient} 内置的 SNAKE_CASE ObjectMapper 负责映射
 * OneBot 的 snake_case JSON（post_type → postType 等），未知字段忽略。
 */
public final class OneBotModels {

    private OneBotModels() {
    }

    public record Segment(String type, Map<String, Object> data) {
    }

    public record Sender(Long userId, String nickname, String card) {
    }

    public record MessageEvent(
            String postType,        // message / notice / request / meta_event
            String messageType,     // private / group
            String subType,
            Long messageId,
            Long userId,
            Long groupId,
            Long selfId,
            List<Segment> message,
            String rawMessage,
            Sender sender,
            Long time) {
    }

    public record ApiResponse(String status, Integer retcode, Map<String, Object> data, String echo) {
    }
}

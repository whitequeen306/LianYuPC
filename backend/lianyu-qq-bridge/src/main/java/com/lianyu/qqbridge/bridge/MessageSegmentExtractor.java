package com.lianyu.qqbridge.bridge;

import com.lianyu.qqbridge.napcat.OneBotModels;

import java.util.List;

/**
 * OneBot 消息段数组 → 纯文本。
 * <p>
 * MVP 仅处理文本：{@code text} 段拼正文；{@code at} 段中对本机器人的 @ 会被剔除（群聊场景），
 * 其余 @ 记为 {@code @<qq>}；图片/表情/回复/合并转发等段忽略。
 */
public final class MessageSegmentExtractor {

    private MessageSegmentExtractor() {
    }

    /**
     * @param segments OneBot message 字段（段数组）
     * @param selfId   本机器人 QQ 号，用于剔除群聊中对自身的 @；为 0 时不剔除
     * @return 拼接后的纯文本（已 trim），无文本时为空串
     */
    public static String toPlainText(List<OneBotModels.Segment> segments, long selfId) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String selfStr = selfId > 0 ? Long.toString(selfId) : null;
        for (OneBotModels.Segment seg : segments) {
            if (seg == null || seg.data() == null) {
                continue;
            }
            String type = seg.type() == null ? "" : seg.type();
            switch (type) {
                case "text" -> {
                    Object t = seg.data().get("text");
                    if (t != null) {
                        sb.append(t.toString());
                    }
                }
                case "at" -> {
                    Object qq = seg.data().get("qq");
                    String who = qq == null ? "" : qq.toString();
                    if (selfStr != null && selfStr.equals(who)) {
                        // 跳过对本机器人的 @，不进入正文
                    } else if ("all".equalsIgnoreCase(who)) {
                        sb.append("@全体 ");
                    } else if (!who.isEmpty()) {
                        sb.append('@').append(who).append(' ');
                    }
                }
                case "image", "face", "reply", "forward", "record", "video" -> {
                    // MVP 仅文本，富媒体段忽略
                }
                default -> {
                    // 未知段忽略
                }
            }
        }
        return sb.toString().trim();
    }
}

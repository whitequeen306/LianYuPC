package com.lianyu.dao.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话活跃度聚合行：某时间窗口内的用户消息数与最近活跃时间。
 * 日记触发等按「当天活跃度」筛会话的场景使用。
 */
@Data
public class ConversationActivityRow {
    private Long conversationId;
    private Long userId;
    private Long characterId;
    private Long userMsgCount;
    private LocalDateTime lastActivityAt;
}

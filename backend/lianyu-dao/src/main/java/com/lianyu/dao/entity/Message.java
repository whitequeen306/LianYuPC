package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long seq;
    private Long conversationId;
    private String role;   // USER / ASSISTANT / SYSTEM / TOOL
    private Long characterId;
    private String content;
    private String imageUrl;
    /** Client asset path (pet/voice/...) or future object-storage URL */
    private String audioUrl;
    private Integer tokens;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

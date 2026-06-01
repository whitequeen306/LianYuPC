package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long characterId;
    private String mode;   // SINGLE / GROUP / COMPANION
    private String title;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

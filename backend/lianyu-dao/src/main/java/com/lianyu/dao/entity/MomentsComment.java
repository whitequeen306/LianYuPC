package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("moments_comment")
public class MomentsComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    private Long userId;
    private String authorType;
    private Long characterId;
    private Long parentId;
    private Long rootId;
    private String content;
    private String sourceType;
    private String idempotencyKey;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

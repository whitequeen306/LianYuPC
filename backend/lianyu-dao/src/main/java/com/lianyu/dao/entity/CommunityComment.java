package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("community_comment")
public class CommunityComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    private Long authorUserId;
    private String content;
    /** pending | published | rejected | deleted */
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

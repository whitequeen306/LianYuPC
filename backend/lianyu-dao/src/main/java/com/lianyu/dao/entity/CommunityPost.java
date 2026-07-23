package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonListTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "community_post", autoResultMap = true)
public class CommunityPost {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorUserId;
    /** Optional character owned by author; shown as badge on the post. */
    private Long linkedCharacterId;
    private String content;
    @TableField(typeHandler = JacksonListTypeHandler.class)
    private List<String> imageUrls;
    /** pending | published | rejected | deleted */
    private String status;
    private Integer likeCount;
    private Integer commentCount;
    private String rejectReason;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

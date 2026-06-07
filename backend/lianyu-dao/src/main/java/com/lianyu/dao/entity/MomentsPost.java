package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "moments_post", autoResultMap = true)
public class MomentsPost {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** CHARACTER | USER */
    private String authorType;
    private Long characterId;
    private String imageUrl;
    private Long conversationId;
    private String content;
    private String postType;
    private String visibility;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metaJson;
    private String sourceHash;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

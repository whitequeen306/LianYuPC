package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "`character`", autoResultMap = true)
public class Character {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerUserId;
    /** 来自角色广场模板 ID，用于判重 */
    private Long sourceTemplateId;
    private String name;
    private String avatarUrl;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> settings;
    private String promptTemplate;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

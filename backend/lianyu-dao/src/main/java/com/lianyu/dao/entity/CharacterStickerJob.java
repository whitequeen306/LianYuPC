package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "character_sticker_job", autoResultMap = true)
public class CharacterStickerJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long characterId;
    private String status;
    private Integer progress;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> traceJson;
    private String errorMessage;
    private Integer retryCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

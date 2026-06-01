package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "character_sticker", autoResultMap = true)
public class CharacterSticker {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long characterId;
    private String objectKey;
    private String publicUrl;
    private String emotion;
    private BigDecimal emotionScore;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tagsJson;
    private String sourceUrl;
    private String sourceSite;
    private String copyrightRiskLevel;
    private Integer enabled;
    private Integer sortOrder;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

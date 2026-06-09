package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonListTypeHandler;
import com.lianyu.dao.enums.MemoryType;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "memory_meta", autoResultMap = true)
public class MemoryMeta {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long characterId;
    private Long userId;
    private String summary;
    private MemoryType memoryType;
    private BigDecimal importance;
    @TableField(typeHandler = JacksonListTypeHandler.class)
    private List<Long> sourceMsgIds;
    private String sourceHash;
    private String milvusVecId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

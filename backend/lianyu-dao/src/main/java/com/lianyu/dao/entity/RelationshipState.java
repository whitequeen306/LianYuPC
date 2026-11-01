package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "relationship_state", autoResultMap = true)
public class RelationshipState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long characterId;
    private Integer trustScore;
    private Integer intimacyScore;
    private Integer securityScore;
    private Integer anticipationScore;
    private String phase;
    private LocalDateTime lastInjuryAt;
    private LocalDateTime lastRepairAt;
    private LocalDateTime lastProactiveAt;
    private LocalDateTime lastInteractionAt;
    private Integer streakDays;
    private java.time.LocalDate streakAnchorDate;
    private LocalDateTime lastDecayAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

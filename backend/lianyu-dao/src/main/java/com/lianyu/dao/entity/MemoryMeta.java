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
    /** 四层：core / fact / episode / reflection（V58）。 */
    private String layer;
    /** 可及性强度：夜间衰减只改这一列与 last_decay_at，不删行。 */
    private BigDecimal strength;
    /** 时间常数（天）；0 表示不衰减（core / pinned）。 */
    private Integer tauDays;
    /** active / faded / archived / dormant。 */
    private String fadeLevel;
    private String status;
    /** 向量投影状态：active / dropped（dormant 时置 dropped，向量不再参与召回）。 */
    private String embeddingState;
    @TableField(value = "last_decay_at")
    private LocalDateTime lastDecayAt;
    private LocalDateTime lastRecalledAt;

    /** 被召回注入过的次数；记忆页用来说明「这条被想起过几次」 */
    private Integer recallCount;
    private LocalDateTime archivedAt;
    private Boolean pinned;
    private Boolean muted;
    /** 事实的有效期（双时间轴）：valid_to 为空表示仍然成立。 */
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    /** 事件发生时间；画像类事实回填为创建时间。 */
    private LocalDateTime eventTime;
    /** 支撑这条记忆的原话（逐字），渲染时优先于 summary。 */
    private String quote;
    /** 被哪一条取代；不物理删，保留可追溯。 */
    private Long supersededBy;
    private Integer revision;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

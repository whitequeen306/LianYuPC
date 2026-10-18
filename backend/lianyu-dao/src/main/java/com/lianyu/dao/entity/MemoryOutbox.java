package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 记忆向量投影 outbox（见 V54 迁移里的背景说明）。
 *
 * <p>业务事务内只写本表一行；由 relay 异步、幂等地投影到 Milvus。
 */
@Data
@TableName("memory_outbox")
public class MemoryOutbox {

    public static final String OP_UPSERT = "UPSERT";
    public static final String OP_DELETE = "DELETE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memoryId;

    /** UPSERT / DELETE */
    private String op;

    /** DELETE 时要删除的旧向量 id（UPSERT 时为空，由 relay 现取现算） */
    private String vecId;

    private String status;

    private Integer attempts;

    private LocalDateTime nextAttemptAt;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一段"共同经历"（V58 的 memory_episode）。
 *
 * <p>与 memory_meta 分开存：事实可以合并、可以 supersede，原话不行——原话是场景的锚，
 * 换一种说法就不再是"她当时真的这么说"。
 */
@Data
@TableName("memory_episode")
public class MemoryEpisode {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long characterId;
    private Long conversationId;
    private String summary;
    /** 用户原话（必填）。记忆页要能给人看"她记住的是哪一句"。 */
    private String quote;
    private LocalDateTime eventTime;
    private BigDecimal valence;
    private BigDecimal arousal;
    /** 当时没接住的话；为空表示已了结。 */
    private String openLoop;
    private String status;
    private LocalDateTime createdAt;
}

package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "moments_interaction_state", autoResultMap = true)
public class MomentsInteractionState {
    @TableId
    private Long postId;
    private Long userId;
    private Integer peerRoundDone;
    private Integer peerRoundSeq;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> lastPeerSampleJson;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

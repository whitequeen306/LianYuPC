package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("user_custom_voice")
public class UserCustomVoice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long characterId;
    /** DASHSCOPE_VC | GPTSOVITS_LOCAL */
    private String provider;
    private String httpVoiceId;
    private String realtimeVoiceId;
    private String refAudioObjectKey;
    private String refText;
    private String endpoint;
    private String apiKeyEncrypted;
    private String keyVersion;
    /** PENDING | READY | FAILED */
    private String status;
    private String errorMessage;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次性绑定码：用户在 App 里生成，在 QQ 里发给机器人，换取一条 {@link QqChannelBinding}。
 * <p>一次性 + 有效期，是为了避免「知道一个 QQ 号就能接管别人的角色」。
 */
@Data
@TableName("qq_bind_code")
public class QqBindCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long userId;
    private Long characterId;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
    private String consumedChannel;
    private String consumedExternalId;
    private LocalDateTime createdAt;
}

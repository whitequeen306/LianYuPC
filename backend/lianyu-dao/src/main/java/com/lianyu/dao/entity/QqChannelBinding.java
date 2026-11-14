package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个外部渠道身份（如 QQ openid）指向哪个用户、哪个角色、哪个会话。
 * <p>放库里的原因：绑定关系是事实数据，网关重启、换机器都不能丢；
 * 反过来网关侧只留一条 HTTP 通道，不存用户凭据。
 */
@Data
@TableName("qq_channel_binding")
public class QqChannelBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** qqbot-c2c / qqbot-group */
    private String channel;
    /** 渠道内的身份：单聊是 user_openid，群聊是 "群openid:成员openid" */
    private String externalId;
    private Long userId;
    private Long characterId;
    private Long conversationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自带的 QQ 官方机器人（BYO）。
 *
 * <p>为什么凭据落在 Java 而不是网关：网关在公网、只做「腾讯↔我们」的搬运，
 * 一旦它持凭据就等于把所有用户的 bot 密码摊在暴露面上。网关只向
 * {@code /internal/qq/token} 要短期 access_token，secret 永不出库。
 */
@Data
@TableName("qq_bot_account")
public class QqBotAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String appId;

    private String botName;

    /** Jasypt 加密后的 appSecret；写入/读取都由 service 层加解密，不挂 TypeHandler（要能拿到密文做判重）。 */
    private String appSecretCipher;

    /** 用户对这个 bot 的 openid：扫码那一刻由腾讯给出，等于「谁在用什么身份说话」。 */
    private String externalOpenid;

    private Long characterId;

    /** active | disabled */
    private String status;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

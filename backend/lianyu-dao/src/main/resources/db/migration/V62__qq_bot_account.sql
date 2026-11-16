-- QQ 官方机器人 BYO（用户自带机器人）：扫码拿到的 appId/appSecret 与角色绑定
-- 为什么单独成表而不复用 qq_channel_binding：凭据是「机器人」的属性，一条凭据可能先后
-- 服务不同角色（换绑），而 binding 记的是「谁在哪个会话里聊」；两者生命周期不同。
CREATE TABLE IF NOT EXISTS `qq_bot_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `user_id` BIGINT NOT NULL COMMENT 'owner user id',
  `app_id` VARCHAR(32) NOT NULL COMMENT 'QQ bot appid',
  `bot_name` VARCHAR(64) DEFAULT NULL COMMENT 'bot nickname in QQ open platform',
  `app_secret_cipher` VARCHAR(255) NOT NULL COMMENT 'app secret, jasypt encrypted',
  `external_openid` VARCHAR(64) DEFAULT NULL COMMENT 'openid of the owner towards this bot',
  `character_id` BIGINT DEFAULT NULL COMMENT 'bound character id',
  `status` VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active|disabled',
  `last_error` VARCHAR(255) DEFAULT NULL COMMENT 'last connection error',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_qq_bot_account_app` (`app_id`),
  UNIQUE KEY `uk_qq_bot_account_openid` (`external_openid`),
  KEY `idx_qq_bot_account_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='QQ official bot credentials owned by users';

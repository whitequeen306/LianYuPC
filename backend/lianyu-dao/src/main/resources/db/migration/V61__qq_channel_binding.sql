-- QQ channel binding: which LianYu user/character a QQ openid talks to.
-- Kept ASCII-only (mixed client charsets corrupt non-ASCII comments).
CREATE TABLE IF NOT EXISTS qq_channel_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel VARCHAR(32) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_qq_channel_binding (channel, external_id),
    KEY idx_qq_channel_binding_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One-time bind codes. A user generates a code in the app and sends it to the bot in QQ.
CREATE TABLE IF NOT EXISTS qq_bind_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(8) NOT NULL,
    user_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME DEFAULT NULL,
    consumed_channel VARCHAR(32) DEFAULT NULL,
    consumed_external_id VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_qq_bind_code (code),
    KEY idx_qq_bind_code_user (user_id, character_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

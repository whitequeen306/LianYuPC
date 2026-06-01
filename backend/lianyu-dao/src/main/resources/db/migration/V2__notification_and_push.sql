CREATE TABLE IF NOT EXISTS user_notification (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    character_id    BIGINT DEFAULT NULL,
    title           VARCHAR(255) NOT NULL,
    content_preview VARCHAR(512) DEFAULT NULL,
    type            VARCHAR(32) NOT NULL DEFAULT 'MESSAGE',
    is_read         TINYINT(1) NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    read_at         DATETIME(3) DEFAULT NULL,
    INDEX idx_user_unread (user_id, is_read, created_at),
    INDEX idx_user_conv (user_id, conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS web_push_subscription (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    endpoint        VARCHAR(1024) NOT NULL,
    p256dh          VARCHAR(512) NOT NULL,
    auth            VARCHAR(512) NOT NULL,
    user_agent      VARCHAR(512) DEFAULT NULL,
    enabled         TINYINT(1) NOT NULL DEFAULT 1,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_endpoint (endpoint(255)),
    INDEX idx_user_enabled (user_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

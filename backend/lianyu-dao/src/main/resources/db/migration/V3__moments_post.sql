CREATE TABLE IF NOT EXISTS moments_post (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    character_id    BIGINT NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    content         VARCHAR(512) NOT NULL,
    post_type       VARCHAR(32) NOT NULL COMMENT 'MOOD | REFLECTION | SYSTEM',
    visibility      VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    meta_json       JSON DEFAULT NULL,
    source_hash     CHAR(64) NOT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_moments_source_hash (source_hash),
    INDEX idx_moments_user_created (user_id, created_at),
    INDEX idx_moments_user_char_created (user_id, character_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

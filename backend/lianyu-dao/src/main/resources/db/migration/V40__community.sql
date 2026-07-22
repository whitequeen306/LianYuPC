-- MySQL 8.4 不支持 ALTER ADD COLUMN IF NOT EXISTS
SET @user_settings_col := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND column_name = 'settings_json'
);
SET @sql_user_settings_col := IF(@user_settings_col = 0,
    'ALTER TABLE `user` ADD COLUMN settings_json JSON DEFAULT NULL COMMENT ''user privacy/prefs JSON'' AFTER avatar_url',
    'SELECT 1');
PREPARE stmt_user_settings_col FROM @sql_user_settings_col;
EXECUTE stmt_user_settings_col;
DEALLOCATE PREPARE stmt_user_settings_col;

CREATE TABLE IF NOT EXISTS community_post (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    author_user_id  BIGINT NOT NULL,
    content         VARCHAR(1000) NOT NULL,
    image_urls      JSON DEFAULT NULL COMMENT '0-9 public file paths or object keys',
    status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending|published|rejected|deleted',
    like_count      INT NOT NULL DEFAULT 0,
    comment_count   INT NOT NULL DEFAULT 0,
    reject_reason   VARCHAR(256) DEFAULT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_community_post_status_id (status, id DESC),
    INDEX idx_community_post_author_status_id (author_user_id, status, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS community_comment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id         BIGINT NOT NULL,
    author_user_id  BIGINT NOT NULL,
    content         VARCHAR(512) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'published' COMMENT 'pending|published|rejected|deleted',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_community_comment_post_id (post_id, id),
    INDEX idx_community_comment_author (author_user_id, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS community_like (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id     BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_community_like_post_user (post_id, user_id),
    INDEX idx_community_like_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

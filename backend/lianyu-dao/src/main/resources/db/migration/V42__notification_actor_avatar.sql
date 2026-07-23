-- MySQL 8.4 不支持 ALTER ADD COLUMN IF NOT EXISTS
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_notification'
      AND COLUMN_NAME = 'actor_avatar_url'
);
SET @sql := IF(
    @col_exists = 0,
    'ALTER TABLE user_notification ADD COLUMN actor_avatar_url VARCHAR(512) DEFAULT NULL COMMENT ''actor avatar URL for toast'' AFTER character_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

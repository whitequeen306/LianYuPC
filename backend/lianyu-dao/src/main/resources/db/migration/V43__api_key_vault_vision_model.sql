-- Optional per-vault default vision/VL model (text model stays in model_default)
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'api_key_vault'
      AND COLUMN_NAME = 'vision_model_default'
);
SET @sql := IF(
    @col_exists = 0,
    'ALTER TABLE api_key_vault ADD COLUMN vision_model_default VARCHAR(128) DEFAULT NULL COMMENT ''default vision/VL model for image chat'' AFTER model_default',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

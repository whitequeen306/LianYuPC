-- api_key_vault.purpose: text（默认，聊天文本模型）| vision（识图/VL 专用）
-- 存量行默认 text；vision_model_default 列废弃（识图改走 purpose=vision 的整条 vault 或平台默认）
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'api_key_vault'
      AND COLUMN_NAME = 'purpose'
);
SET @sql := IF(
    @col_exists = 0,
    'ALTER TABLE api_key_vault ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT ''text'' COMMENT ''vault purpose: text|vision'' AFTER model_default',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- User-selectable DashScope API base + VC model names (recommended defaults in app UI).
SET @col_http := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user_custom_voice'
      AND column_name = 'http_model'
);
SET @sql_http := IF(@col_http = 0,
    'ALTER TABLE user_custom_voice ADD COLUMN http_model VARCHAR(64) NULL COMMENT ''DashScope HTTP VC model'' AFTER endpoint',
    'SELECT 1');
PREPARE stmt_http FROM @sql_http;
EXECUTE stmt_http;
DEALLOCATE PREPARE stmt_http;

SET @col_rt := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user_custom_voice'
      AND column_name = 'realtime_model'
);
SET @sql_rt := IF(@col_rt = 0,
    'ALTER TABLE user_custom_voice ADD COLUMN realtime_model VARCHAR(64) NULL COMMENT ''DashScope realtime VC model'' AFTER http_model',
    'SELECT 1');
PREPARE stmt_rt FROM @sql_rt;
EXECUTE stmt_rt;
DEALLOCATE PREPARE stmt_rt;

-- api_key_vault：平台 DEFAULT 池 + 用户 USER 作用域（MySQL 8.4 不支持 ADD COLUMN IF NOT EXISTS）

SET @vault_scope_col := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'api_key_vault'
      AND column_name = 'vault_scope'
);
SET @sql_vault_scope_col := IF(@vault_scope_col = 0,
    'ALTER TABLE api_key_vault ADD COLUMN vault_scope VARCHAR(16) NOT NULL DEFAULT ''USER'' COMMENT ''USER / DEFAULT'' AFTER provider',
    'SELECT 1');
PREPARE stmt_vault_scope_col FROM @sql_vault_scope_col;
EXECUTE stmt_vault_scope_col;
DEALLOCATE PREPARE stmt_vault_scope_col;

SET @enabled_col := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'api_key_vault'
      AND column_name = 'enabled'
);
SET @sql_enabled_col := IF(@enabled_col = 0,
    'ALTER TABLE api_key_vault ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''1=enabled, 0=disabled'' AFTER vault_scope',
    'SELECT 1');
PREPARE stmt_enabled_col FROM @sql_enabled_col;
EXECUTE stmt_enabled_col;
DEALLOCATE PREPARE stmt_enabled_col;

SET @remark_col := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'api_key_vault'
      AND column_name = 'remark'
);
SET @sql_remark_col := IF(@remark_col = 0,
    'ALTER TABLE api_key_vault ADD COLUMN remark VARCHAR(255) DEFAULT NULL COMMENT ''Operator note'' AFTER enabled',
    'SELECT 1');
PREPARE stmt_remark_col FROM @sql_remark_col;
EXECUTE stmt_remark_col;
DEALLOCATE PREPARE stmt_remark_col;

ALTER TABLE api_key_vault
    MODIFY COLUMN user_id BIGINT NULL;

UPDATE api_key_vault
SET vault_scope = 'USER'
WHERE vault_scope IS NULL OR vault_scope = '';

SET @old_uk := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'api_key_vault'
      AND index_name = 'uk_user_provider'
);
SET @sql_drop_old_uk := IF(@old_uk > 0,
    'ALTER TABLE api_key_vault DROP INDEX uk_user_provider',
    'SELECT 1');
PREPARE stmt_drop_old_uk FROM @sql_drop_old_uk;
EXECUTE stmt_drop_old_uk;
DEALLOCATE PREPARE stmt_drop_old_uk;

SET @new_uk := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'api_key_vault'
      AND index_name = 'uk_user_provider_scope'
);
SET @sql_new_uk := IF(@new_uk = 0,
    'ALTER TABLE api_key_vault ADD UNIQUE KEY uk_user_provider_scope (user_id, provider, vault_scope)',
    'SELECT 1');
PREPARE stmt_new_uk FROM @sql_new_uk;
EXECUTE stmt_new_uk;
DEALLOCATE PREPARE stmt_new_uk;

SET @idx_scope_provider := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'api_key_vault'
      AND index_name = 'idx_scope_provider_enabled'
);
SET @sql_idx_scope_provider := IF(@idx_scope_provider = 0,
    'ALTER TABLE api_key_vault ADD INDEX idx_scope_provider_enabled (vault_scope, provider, enabled)',
    'SELECT 1');
PREPARE stmt_idx_scope_provider FROM @sql_idx_scope_provider;
EXECUTE stmt_idx_scope_provider;
DEALLOCATE PREPARE stmt_idx_scope_provider;

SET @idx_scope_enabled := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'api_key_vault'
      AND index_name = 'idx_scope_enabled_updated'
);
SET @sql_idx_scope_enabled := IF(@idx_scope_enabled = 0,
    'ALTER TABLE api_key_vault ADD INDEX idx_scope_enabled_updated (vault_scope, enabled, updated_at)',
    'SELECT 1');
PREPARE stmt_idx_scope_enabled FROM @sql_idx_scope_enabled;
EXECUTE stmt_idx_scope_enabled;
DEALLOCATE PREPARE stmt_idx_scope_enabled;

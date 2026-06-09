SET @importance_col := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'memory_meta'
      AND column_name = 'importance'
);
SET @sql_importance_col := IF(@importance_col = 0,
    'ALTER TABLE memory_meta ADD COLUMN importance DECIMAL(3,2) NOT NULL DEFAULT 0.50 AFTER memory_type',
    'SELECT 1');
PREPARE stmt_importance_col FROM @sql_importance_col;
EXECUTE stmt_importance_col;
DEALLOCATE PREPARE stmt_importance_col;

SET @importance_idx := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'memory_meta'
      AND index_name = 'idx_char_user_importance'
);
SET @sql_importance_idx := IF(@importance_idx = 0,
    'CREATE INDEX idx_char_user_importance ON memory_meta (character_id, user_id, importance)',
    'SELECT 1');
PREPARE stmt_importance_idx FROM @sql_importance_idx;
EXECUTE stmt_importance_idx;
DEALLOCATE PREPARE stmt_importance_idx;

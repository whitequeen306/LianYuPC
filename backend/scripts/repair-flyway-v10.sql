-- 修复 V10 失败：清 Flyway 记录 + 回滚半完成 DDL，便于用修正后的 V10 重新迁移
-- 用法: Get-Content backend\scripts\repair-flyway-v10.sql | docker exec -i lianyu-mysql mysql -uroot -proot123 lianyu

DELETE FROM flyway_schema_history WHERE version = '10';

SET @idx_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'character'
      AND index_name = 'uk_owner_source_template'
);
SET @sql_drop_idx := IF(@idx_exists > 0,
    'ALTER TABLE `character` DROP INDEX uk_owner_source_template',
    'SELECT 1');
PREPARE stmt_drop_idx FROM @sql_drop_idx;
EXECUTE stmt_drop_idx;
DEALLOCATE PREPARE stmt_drop_idx;

SET @col_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'character'
      AND column_name = 'source_template_id'
);
SET @sql_drop_col := IF(@col_exists > 0,
    'ALTER TABLE `character` DROP COLUMN source_template_id',
    'SELECT 1');
PREPARE stmt_drop_col FROM @sql_drop_col;
EXECUTE stmt_drop_col;
DEALLOCATE PREPARE stmt_drop_col;

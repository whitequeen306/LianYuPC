-- CL-017: prevent duplicate SINGLE conversations per user+character (keep oldest row).
DELETE c1 FROM conversation c1
INNER JOIN conversation c2
  ON c1.user_id = c2.user_id
 AND c1.character_id = c2.character_id
 AND c1.mode = 'SINGLE'
 AND c2.mode = 'SINGLE'
 AND c1.id > c2.id;

SET @conv_uk := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'conversation'
      AND index_name = 'uk_conversation_user_character_mode'
);
SET @sql_conv_uk := IF(@conv_uk = 0,
    'CREATE UNIQUE INDEX uk_conversation_user_character_mode ON conversation (user_id, character_id, mode)',
    'SELECT 1');
PREPARE stmt_conv_uk FROM @sql_conv_uk;
EXECUTE stmt_conv_uk;
DEALLOCATE PREPARE stmt_conv_uk;

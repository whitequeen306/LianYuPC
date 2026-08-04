-- Speed role-filtered latest/count queries on message
-- (selectLatestUser/AssistantByConversationIds, selectUserMessageCountsSince).
SET @msg_role_seq_idx := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'message'
      AND index_name = 'idx_msg_conv_role_seq'
);
SET @sql_msg_role_seq_idx := IF(@msg_role_seq_idx = 0,
    'CREATE INDEX idx_msg_conv_role_seq ON message (conversation_id, role, seq)',
    'SELECT 1');
PREPARE stmt_msg_role_seq_idx FROM @sql_msg_role_seq_idx;
EXECUTE stmt_msg_role_seq_idx;
DEALLOCATE PREPARE stmt_msg_role_seq_idx;

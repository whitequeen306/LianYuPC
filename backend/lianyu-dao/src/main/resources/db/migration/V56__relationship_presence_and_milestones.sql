-- Relationship rework: presence + decay bookkeeping + milestone timeline.
-- Scores and phase semantics stay backward compatible; only additive columns.

SET @rel_last_interaction := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'relationship_state'
      AND column_name = 'last_interaction_at'
);
SET @sql_rel_last_interaction := IF(@rel_last_interaction = 0,
    'ALTER TABLE relationship_state ADD COLUMN last_interaction_at DATETIME(3) DEFAULT NULL AFTER last_proactive_at',
    'SELECT 1');
PREPARE stmt_rel_last_interaction FROM @sql_rel_last_interaction;
EXECUTE stmt_rel_last_interaction;
DEALLOCATE PREPARE stmt_rel_last_interaction;

SET @rel_streak_days := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'relationship_state'
      AND column_name = 'streak_days'
);
SET @sql_rel_streak_days := IF(@rel_streak_days = 0,
    'ALTER TABLE relationship_state ADD COLUMN streak_days INT NOT NULL DEFAULT 0 AFTER last_interaction_at',
    'SELECT 1');
PREPARE stmt_rel_streak_days FROM @sql_rel_streak_days;
EXECUTE stmt_rel_streak_days;
DEALLOCATE PREPARE stmt_rel_streak_days;

SET @rel_streak_anchor := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'relationship_state'
      AND column_name = 'streak_anchor_date'
);
SET @sql_rel_streak_anchor := IF(@rel_streak_anchor = 0,
    'ALTER TABLE relationship_state ADD COLUMN streak_anchor_date DATE DEFAULT NULL AFTER streak_days',
    'SELECT 1');
PREPARE stmt_rel_streak_anchor FROM @sql_rel_streak_anchor;
EXECUTE stmt_rel_streak_anchor;
DEALLOCATE PREPARE stmt_rel_streak_anchor;

SET @rel_last_decay := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'relationship_state'
      AND column_name = 'last_decay_at'
);
SET @sql_rel_last_decay := IF(@rel_last_decay = 0,
    'ALTER TABLE relationship_state ADD COLUMN last_decay_at DATETIME(3) DEFAULT NULL AFTER streak_anchor_date',
    'SELECT 1');
PREPARE stmt_rel_last_decay FROM @sql_rel_last_decay;
EXECUTE stmt_rel_last_decay;
DEALLOCATE PREPARE stmt_rel_last_decay;

-- Backfill from updated_at so existing users do not get a bogus "first contact" milestone
-- on their next message, and so decay starts from a sane point instead of NULL.
UPDATE relationship_state
SET last_interaction_at = updated_at
WHERE last_interaction_at IS NULL;

-- Decay scans ordered by last_interaction_at; without this index it is a full scan + filesort
-- every tick. MySQL has no CREATE INDEX IF NOT EXISTS, so guard via information_schema.
SET @rel_interaction_idx := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'relationship_state'
      AND index_name = 'idx_relationship_state_interaction'
);
SET @sql_rel_interaction_idx := IF(@rel_interaction_idx = 0,
    'CREATE INDEX idx_relationship_state_interaction ON relationship_state (last_interaction_at)',
    'SELECT 1');
PREPARE stmt_rel_interaction_idx FROM @sql_rel_interaction_idx;
EXECUTE stmt_rel_interaction_idx;
DEALLOCATE PREPARE stmt_rel_interaction_idx;

CREATE TABLE IF NOT EXISTS relationship_milestone (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    milestone_key VARCHAR(48) NOT NULL,
    happened_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_relationship_milestone (user_id, character_id, milestone_key),
    INDEX idx_relationship_milestone_character (character_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

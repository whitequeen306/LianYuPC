-- CL-017: prevent duplicate SINGLE conversations per user+character (keep oldest row).
DELETE c1 FROM conversation c1
INNER JOIN conversation c2
  ON c1.user_id = c2.user_id
 AND c1.character_id = c2.character_id
 AND c1.mode = 'SINGLE'
 AND c2.mode = 'SINGLE'
 AND c1.id > c2.id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_user_character_mode
    ON conversation (user_id, character_id, mode);

-- Desktop capability audit: one row per attempted `desk_*` action.
--
-- Why a table (and not just logs): the capability layer can type, click and launch apps on
-- the user's machine. "What did it do to my PC last night" must be answerable, and the
-- product surfaces a "recent actions" panel. Logs rotate; this does not.
--
-- Why digests instead of arguments: args may contain paths, window titles or clipboard text.
-- We store sha256(args)[0:16] so a row can be correlated with the client-side ring buffer
-- without putting private content in the database.
--
-- Ownership: written by Java (AgentBridgeService), never by the Python agent
-- (Python is read-only on MySQL). A failed audit write must never block an action.
--
-- NOTE: keep this file ASCII-only (see V54 for the charset reason).
CREATE TABLE IF NOT EXISTS `agent_capability_audit` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT      NOT NULL,
    `session_id`   VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'one per user turn',
    `step_id`      VARCHAR(96) NOT NULL DEFAULT '' COMMENT 'idempotency key: {session}#{n}',
    `tool`         VARCHAR(64) NOT NULL COMMENT 'desk_* capability name',
    `args_digest`  CHAR(16)    NOT NULL DEFAULT '' COMMENT 'sha256(args) prefix, never plaintext',
    `danger`       CHAR(2)     NOT NULL DEFAULT '' COMMENT 'R0 / R1 / R2 / R3 (after escalation)',
    `evidence`     VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'user_request / screen_content',
    `confirmed`    TINYINT     NOT NULL DEFAULT 0 COMMENT 'user approved a confirmation dialog',
    `ok`           TINYINT     NOT NULL DEFAULT 0,
    `error_code`   VARCHAR(64) NOT NULL DEFAULT '',
    `elapsed_ms`   INT         NOT NULL DEFAULT 0,
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_cap_audit_user_time` (`user_id`, `created_at`),
    KEY `idx_cap_audit_step` (`step_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'desktop capability audit trail';

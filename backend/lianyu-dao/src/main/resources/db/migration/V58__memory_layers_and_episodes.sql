-- Memory v3: four layers (core / fact / episode / reflection) + decay state.
--
-- Why this migration:
--   * The old schema only had (summary, memory_type, importance). "What happened",
--     "when it happened" and "whether it is still valid" had nowhere to live, so
--     retrieval degenerated into "top 20 by an LLM-assigned importance".
--   * Forgetting had no state either: rows were either present or physically deleted.
--
-- This migration is additive only. Existing rows keep working: every new column has
-- a default that reproduces today's behaviour (layer='fact', status='active',
-- strength=1.0, fade_level='active').
--
-- NOTE: keep this file ASCII-only. Non-ASCII comments broke the mysql CLI when the
-- client/server charset differed, and migrations must be charset-agnostic.

CREATE TABLE IF NOT EXISTS `memory_episode` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`          BIGINT       NOT NULL,
    `character_id`     BIGINT       NOT NULL,
    `conversation_id`  BIGINT       NULL COMMENT 'where the scene happened',
    `scene_json`       JSON         NULL COMMENT 'SceneCard snapshot at close time',
    `summary`          VARCHAR(500) NOT NULL COMMENT 'one-sentence gist, gist-level recall',
    `quote`            VARCHAR(600) NOT NULL COMMENT 'verbatim line that anchors the episode',
    `event_time`       DATETIME     NULL COMMENT 'when it happened (not when it was written)',
    `valence`          DECIMAL(3,2) NULL COMMENT '-1.00 .. 1.00',
    `arousal`          DECIMAL(3,2) NULL COMMENT '0.00 .. 1.00, drives decay speed',
    `open_loop`        VARCHAR(200) NULL COMMENT 'unfinished promise / question, if any',
    `source_msg_ids`   JSON         NULL,
    `content_hash`     CHAR(64)     NOT NULL COMMENT 'SHA-256(user+character+quote+event_time)',
    `embedding_state`  VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / done / dropped',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / archived',
    `created_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ep_hash` (`user_id`, `character_id`, `content_hash`),
    KEY `idx_ep_char_time` (`character_id`, `event_time`),
    KEY `idx_ep_conv` (`conversation_id`),
    FULLTEXT KEY `ft_ep_summary` (`summary`) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'episode layer: what happened, with the line that proves it';

ALTER TABLE `memory_meta`
    ADD COLUMN `layer`            VARCHAR(16)  NOT NULL DEFAULT 'fact' COMMENT 'core / fact / episode / reflection',
    ADD COLUMN `subject`          VARCHAR(64)  NULL COMMENT 'who/what this fact is about',
    ADD COLUMN `predicate`        VARCHAR(64)  NULL COMMENT 'likes / avoids / lives_in ...',
    ADD COLUMN `event_time`       DATETIME     NULL COMMENT 'valid time, not write time',
    ADD COLUMN `valid_from`       DATETIME     NULL COMMENT 'bi-temporal: when it became true',
    ADD COLUMN `valid_to`         DATETIME     NULL COMMENT 'NULL = still true',
    ADD COLUMN `status`           VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / superseded / muted',
    ADD COLUMN `superseded_by`    BIGINT       NULL COMMENT 'memory_meta.id that replaced this row',
    ADD COLUMN `source_episode_id` BIGINT      NULL COMMENT 'memory_episode.id this fact came from',
    ADD COLUMN `content_hash`     CHAR(64)     NULL COMMENT 'for dedup / noop detection',
    ADD COLUMN `embedding_state`  VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / done / dropped',
    ADD COLUMN `pinned`           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'user pinned, never decays',
    ADD COLUMN `muted`            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'do not bring up unless asked',
    ADD COLUMN `quote`            VARCHAR(600) NULL COMMENT 'verbatim source line',
    ADD COLUMN `revision`         INT          NOT NULL DEFAULT 0 COMMENT 'incremented on reconsolidation',
    ADD COLUMN `strength`         DECIMAL(4,3) NOT NULL DEFAULT 1.000 COMMENT 'decays, 0..1',
    ADD COLUMN `tau_days`         INT          NOT NULL DEFAULT 365 COMMENT '0 = never decays',
    ADD COLUMN `fade_level`       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / faded / archived / dormant',
    ADD COLUMN `last_decay_at`    DATETIME     NULL COMMENT 'last materialized decay settlement',
    ADD COLUMN `last_recalled_at` DATETIME     NULL,
    ADD COLUMN `last_injected_at` DATETIME     NULL COMMENT 'cooldown: do not repeat within 3 days',
    ADD COLUMN `recall_count`     INT          NOT NULL DEFAULT 0,
    ADD COLUMN `revival_count`    INT          NOT NULL DEFAULT 0,
    ADD COLUMN `archived_at`      DATETIME     NULL;

ALTER TABLE `memory_meta`
    ADD KEY `idx_mem_layer` (`character_id`, `layer`, `status`),
    ADD KEY `idx_mem_valid` (`character_id`, `valid_from`, `valid_to`),
    ADD KEY `idx_mem_decay` (`status`, `last_decay_at`),
    ADD KEY `idx_mem_hash` (`user_id`, `character_id`, `content_hash`),
    ADD FULLTEXT KEY `ft_mem_summary` (`summary`) WITH PARSER ngram;

-- Backfill: existing rows are facts written by the old extractor. Their created_at is
-- the best event_time / valid_from we have. The decay clock starts NOW, not at
-- created_at: starting the clock in the past would archive the whole table on deploy.
UPDATE `memory_meta`
SET `event_time`    = `created_at`,
    `valid_from`    = `created_at`,
    `last_decay_at` = NOW()
WHERE `event_time` IS NULL;

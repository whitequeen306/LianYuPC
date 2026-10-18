-- Memory write outbox: decouples "write MySQL" from "project to Milvus".
--
-- Problem (audit C2): the previous flow did three steps across two stores with no transaction:
--   memory_meta insert -> Milvus insert -> updateById(vecId)
-- Any failure in between produced either (a) a memory row that can never be retrieved
-- (vec_id empty) or (b) an orphan vector (row rolled back, vector already written).
-- It also put a network call inside the business path, violating the
-- "no slow calls inside transactions" red line.
--
-- Fix: inside the business transaction we only write memory_meta + one row here.
-- A relay then projects to Milvus asynchronously, idempotently, with exponential backoff.
--
-- NOTE: keep this file ASCII-only. Non-ASCII comments broke the mysql CLI when the
-- client/server charset differed, and migrations must be charset-agnostic.
CREATE TABLE IF NOT EXISTS `memory_outbox` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `memory_id`       BIGINT       NOT NULL COMMENT 'memory_meta.id',
    `op`              VARCHAR(16)  NOT NULL COMMENT 'UPSERT / DELETE',
    `vec_id`          VARCHAR(64)  NULL     COMMENT 'old vector id to delete (DELETE op only)',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / DONE / DEAD',
    `attempts`        INT          NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_error`      VARCHAR(500) NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_outbox_pick` (`status`, `next_attempt_at`),
    KEY `idx_outbox_memory` (`memory_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'memory vector projection outbox (idempotent retry)';

-- No backfill needed: rows that already have a vec_id stay as-is; rows missing one are
-- picked up by MemoryMilvusSyncService's startup resync or by the relay on next change.

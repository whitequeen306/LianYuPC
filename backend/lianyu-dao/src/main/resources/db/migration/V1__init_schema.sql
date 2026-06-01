-- V1__init_schema.sql — Initial schema per PLAN-001 §8
-- All DDL uses IF NOT EXISTS for Flyway reentrant safety

CREATE TABLE IF NOT EXISTS `user` (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    password_hash  CHAR(60) NOT NULL COMMENT 'BCrypt hash, cost=10',
    nickname    VARCHAR(128) DEFAULT NULL,
    avatar_url  VARCHAR(512) DEFAULT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `character` (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_user_id   BIGINT NOT NULL,
    name            VARCHAR(128) NOT NULL,
    avatar_url      VARCHAR(512) DEFAULT NULL,
    settings        JSON DEFAULT NULL COMMENT 'Character personality settings',
    prompt_template TEXT DEFAULT NULL COMMENT 'System prompt template with placeholders',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    character_id    BIGINT DEFAULT NULL COMMENT 'NULL for group chat',
    mode            VARCHAR(16) NOT NULL DEFAULT 'SINGLE' COMMENT 'SINGLE / GROUP / COMPANION',
    title           VARCHAR(256) DEFAULT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_user (user_id),
    INDEX idx_user_mode (user_id, mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS group_member (
    conversation_id BIGINT NOT NULL,
    character_id    BIGINT NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    seq             BIGINT NOT NULL COMMENT 'Monotonic sequence per conversation',
    conversation_id BIGINT NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT / SYSTEM / TOOL',
    character_id    BIGINT DEFAULT NULL COMMENT 'NULL when role=USER',
    content         MEDIUMTEXT DEFAULT NULL,
    tokens          INT DEFAULT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conv_seq (conversation_id, seq),
    INDEX idx_conv_created (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS memory_meta (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    character_id    BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    summary         TEXT DEFAULT NULL,
    source_msg_ids  JSON DEFAULT NULL COMMENT 'Source message IDs that produced this memory',
    source_hash     CHAR(64) DEFAULT NULL COMMENT 'SHA-256(sorted(source_msg_ids)) for dedup',
    milvus_vec_id   VARCHAR(64) DEFAULT NULL COMMENT 'Corresponding vector ID in Milvus',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_source_hash (source_hash),
    INDEX idx_char_user (character_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_key_vault (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    provider            VARCHAR(32) NOT NULL COMMENT 'openai / gemini / ollama / partner',
    api_key_encrypted   TEXT NOT NULL COMMENT 'Jasypt AES-GCM encrypted',
    key_version         VARCHAR(16) NOT NULL COMMENT 'Master key version used for encryption',
    base_url            VARCHAR(512) DEFAULT NULL,
    model_default       VARCHAR(128) DEFAULT NULL,
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_provider (user_id, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

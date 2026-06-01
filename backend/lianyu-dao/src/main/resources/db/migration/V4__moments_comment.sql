CREATE TABLE IF NOT EXISTS moments_comment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id         BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    author_type     VARCHAR(16) NOT NULL COMMENT 'USER | CHARACTER',
    character_id    BIGINT DEFAULT NULL,
    parent_id       BIGINT DEFAULT NULL,
    root_id         BIGINT DEFAULT NULL,
    content         VARCHAR(512) NOT NULL,
    source_type     VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    idempotency_key VARCHAR(128) DEFAULT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_moments_comment_idem (idempotency_key),
    INDEX idx_moments_comment_post (post_id, created_at),
    INDEX idx_moments_comment_root (root_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS moments_interaction_state (
    post_id             BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    peer_round_done     TINYINT(1) NOT NULL DEFAULT 0,
    peer_round_seq      INT NOT NULL DEFAULT 0,
    last_peer_sample_json JSON DEFAULT NULL,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

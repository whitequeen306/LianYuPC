-- Per-user per-character custom voice (isolated from official pet-voices.json).
CREATE TABLE IF NOT EXISTS user_custom_voice (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    character_id    BIGINT       NOT NULL,
    provider        VARCHAR(32)  NOT NULL COMMENT 'DASHSCOPE_VC | GPTSOVITS_LOCAL',
    http_voice_id   VARCHAR(128) NULL COMMENT 'DashScope HTTP VC voice id',
    realtime_voice_id VARCHAR(128) NULL COMMENT 'DashScope realtime VC voice id',
    ref_audio_object_key VARCHAR(512) NULL COMMENT 'MinIO object key under custom-voices/',
    ref_text        TEXT         NULL COMMENT 'Reference transcript for zero-shot local TTS',
    endpoint        VARCHAR(512) NULL COMMENT 'Local TTS base URL; never fetched by backend',
    api_key_encrypted TEXT       NULL COMMENT 'User DashScope key (Jasypt); null for local',
    key_version     VARCHAR(16)  NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|READY|FAILED',
    error_message   VARCHAR(512) NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_custom_voice_user_char (user_id, character_id),
    KEY idx_user_custom_voice_char (character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS character_sticker_job (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    character_id    BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
    progress        INT          NOT NULL DEFAULT 0 COMMENT '0-100',
    trace_json      JSON         DEFAULT NULL,
    error_message   VARCHAR(512) DEFAULT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sticker_job_status (status, created_at),
    INDEX idx_sticker_job_character (character_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS character_sticker (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id              BIGINT       NOT NULL,
    character_id         BIGINT       NOT NULL,
    object_key           VARCHAR(256) NOT NULL,
    public_url           VARCHAR(512) NOT NULL,
    emotion              VARCHAR(32)  NOT NULL COMMENT 'HAPPY/ANGRY/CONFUSED/SHY/SPEECHLESS/SAD/PROUD/NEUTRAL',
    emotion_score        DECIMAL(4,3) DEFAULT NULL,
    tags_json            JSON         DEFAULT NULL,
    source_url           VARCHAR(1024) DEFAULT NULL,
    source_site          VARCHAR(128)  DEFAULT NULL,
    copyright_risk_level VARCHAR(16)   NOT NULL DEFAULT 'LOW' COMMENT 'LOW/MEDIUM/HIGH',
    enabled              TINYINT      NOT NULL DEFAULT 1,
    sort_order           INT          NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sticker_character_enabled (character_id, enabled, emotion),
    INDEX idx_sticker_user (user_id, character_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

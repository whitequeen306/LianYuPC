ALTER TABLE message
    ADD COLUMN image_url VARCHAR(512) DEFAULT NULL COMMENT 'Optional image attachment for user messages' AFTER content;

ALTER TABLE message
    ADD COLUMN sticker_url VARCHAR(512) DEFAULT NULL COMMENT 'Optional sticker image for assistant messages' AFTER image_url;

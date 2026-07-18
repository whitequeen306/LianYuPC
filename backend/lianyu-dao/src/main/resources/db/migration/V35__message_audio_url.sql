-- Fixed / attached audio clips for chat messages (e.g. first-meet pet voice).
ALTER TABLE message
    ADD COLUMN audio_url VARCHAR(512) DEFAULT NULL COMMENT 'Optional audio clip URL or client asset path' AFTER image_url;

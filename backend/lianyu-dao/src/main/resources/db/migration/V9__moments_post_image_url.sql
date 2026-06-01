ALTER TABLE moments_post
    ADD COLUMN image_url VARCHAR(512) DEFAULT NULL COMMENT 'Optional generated meme image URL' AFTER content;

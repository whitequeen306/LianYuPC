ALTER TABLE community_post
    ADD COLUMN linked_character_id BIGINT NULL COMMENT 'optional linked character owned by author';

CREATE INDEX idx_community_post_author_character
    ON community_post (author_user_id, linked_character_id, status, id DESC);

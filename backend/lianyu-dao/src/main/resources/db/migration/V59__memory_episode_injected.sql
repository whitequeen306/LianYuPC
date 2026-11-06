-- Episodes need the same "when did we last show this" clock as memory_meta.
-- Without it an episode is eligible every single turn and crowds out everything else.
ALTER TABLE memory_episode
    ADD COLUMN last_injected_at DATETIME NULL AFTER updated_at;

CREATE INDEX idx_ep_injected ON memory_episode (last_injected_at);

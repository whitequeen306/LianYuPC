-- Optional model-facing text for messages (e.g. voice-call summary bubble).
-- When set, LLM history uses context_content instead of content; UI still shows content.
ALTER TABLE message
    ADD COLUMN context_content TEXT NULL COMMENT 'Optional model-facing content; overrides content in LLM history when set' AFTER content;

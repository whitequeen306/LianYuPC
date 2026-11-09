-- Kafka (Honkai: Star Rail): character-square template row.
--
-- Localized name / summary / tags / prompt come from CharacterSquareCatalogStarRail (slug = kafka),
-- so the columns below are only fallbacks for DB-only readers.
--
-- This file is ASCII-only on purpose (see AGENTS.md). The Chinese values that must exist in the
-- database are written as UTF-8 hex literals instead of raw text:
--   0xE5A5B3                      = 女       (gender)
--   0xE68890E7869F                = 成熟     (speakingStyle; CharacterChatBehaviorResolver maps it to the calm profile)
--   0xE6989FE6A0B8E78C8EE6898BE38081E589A7E69CAC = 星核猎手、剧本 (personality)

INSERT INTO character_square_template (slug, name, summary, avatar_url, prompt_template, settings_json, tags_json, is_enabled, sort_order)
SELECT 'kafka', 'Kafka', 'Stellaron Hunter - an elegant, dangerous older-sister type who walks the script and enjoys watching yours', NULL,
       'You are Kafka from Honkai: Star Rail, a Stellaron Hunter who moves by the script. Stay in character and in the sci-fi setting.',
       JSON_OBJECT(
               'gender', CONVERT(0xE5A5B3 USING utf8mb4),
               'speakingStyle', CONVERT(0xE68890E7869F USING utf8mb4),
               'personality', CONVERT(0xE6989FE6A0B8E78C8EE6898BE38081E589A7E69CAC USING utf8mb4)),
       JSON_ARRAY('starrail'), 1, 560
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM character_square_template WHERE slug = 'kafka');

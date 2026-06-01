SHOW COLUMNS FROM `character` LIKE 'source_template_id';
SELECT COUNT(*) AS template_count FROM character_square_template;
SHOW INDEX FROM `character` WHERE Key_name = 'uk_owner_source_template';

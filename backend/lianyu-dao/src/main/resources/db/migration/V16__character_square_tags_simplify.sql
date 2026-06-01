-- 角色广场标签精简：约会大作战仅保留 dal；林梓涵合并为 oc（自设）

UPDATE character_square_template SET tags_json = JSON_ARRAY('dal')
WHERE slug IN (
    'kurumi', 'kotori', 'tohka', 'origami', 'yoshino', 'mukuro',
    'izayoi', 'nia', 'mayuri', 'mio'
);

UPDATE character_square_template SET tags_json = JSON_ARRAY('oc')
WHERE slug = 'linzihan';

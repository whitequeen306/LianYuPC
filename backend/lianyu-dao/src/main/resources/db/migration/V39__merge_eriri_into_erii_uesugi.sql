-- 合并重复「上杉绘梨衣」：保留带头像的 erii_uesugi，下线误加的 eriri，并把 VC/计数归到前者。

-- 1) 累计添加次数合并到 erii_uesugi
UPDATE character_square_template keep_t
INNER JOIN character_square_template drop_t ON drop_t.slug = 'eriri'
SET keep_t.add_count = IFNULL(keep_t.add_count, 0) + IFNULL(drop_t.add_count, 0)
WHERE keep_t.slug = 'erii_uesugi';

-- 2) 已加入 eriri 且尚未加入 erii_uesugi 的角色，改挂到保留模板
UPDATE `character` c
INNER JOIN character_square_template drop_t ON drop_t.slug = 'eriri' AND c.source_template_id = drop_t.id
INNER JOIN character_square_template keep_t ON keep_t.slug = 'erii_uesugi'
LEFT JOIN `character` existing
       ON existing.owner_user_id = c.owner_user_id
      AND existing.source_template_id = keep_t.id
SET c.source_template_id = keep_t.id,
    c.avatar_url = COALESCE(NULLIF(c.avatar_url, ''), keep_t.avatar_url)
WHERE existing.id IS NULL;

-- 3) 下线重复模板（保留行便于审计，广场不再展示）
UPDATE character_square_template
SET is_enabled = 0,
    name = '上杉绘梨衣（已合并）'
WHERE slug = 'eriri';

-- 4) 规范化保留模板标签为 key（与 catalog 一致）
UPDATE character_square_template
SET tags_json = JSON_ARRAY('longzu', 'gentle')
WHERE slug = 'erii_uesugi';

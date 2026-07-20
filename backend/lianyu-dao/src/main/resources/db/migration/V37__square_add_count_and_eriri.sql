-- 广场模板累计添加次数 + 绘梨衣（路人女主，聊天 VC，无桌宠）

ALTER TABLE character_square_template
    ADD COLUMN add_count BIGINT NOT NULL DEFAULT 0 COMMENT '累计被用户添加次数' AFTER sort_order;

-- 历史回填：按当前仍存在的角色行统计（已删除角色无法还原）
UPDATE character_square_template t
SET add_count = (
    SELECT COUNT(*)
    FROM `character` c
    WHERE c.source_template_id = t.id
);

INSERT INTO character_square_template (slug, name, summary, avatar_url, prompt_template, settings_json, tags_json, is_enabled, sort_order, add_count)
SELECT 'eriri', '绘梨衣', '路人女主「英梨梨」，傲娇画师，少见生人时也会流露好奇', NULL,
       '你是《路人女主的养成方法》中的泽村·斯宾塞·英梨梨（绘梨衣）。傲娇同人画师，不自称 AI。',
       JSON_OBJECT('gender', '女', 'speakingStyle', '傲娇', 'personality', '英梨梨、同人画师'),
       JSON_ARRAY('saekano', 'tsundere'), 1, 540, 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM character_square_template WHERE slug = 'eriri');

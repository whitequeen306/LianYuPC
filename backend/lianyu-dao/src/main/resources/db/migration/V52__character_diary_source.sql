-- V52__character_diary_source.sql — 日记来源标记
-- SCHEDULED=定时算法（22:00/23:30 扫描）；IMPROMPTU=角色聊天中调 write_diary 工具即兴生成。
-- 两路「每天一篇」互斥各自独立：定时互斥只数 SCHEDULED，即兴互斥只数 IMPROMPTU；
-- 两天保底按任何来源的最后一篇计算。
ALTER TABLE character_diary
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'
        COMMENT '日记来源：SCHEDULED=定时算法，IMPROMPTU=角色即兴工具' AFTER conversation_id;

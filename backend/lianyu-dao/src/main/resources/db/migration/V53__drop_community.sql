-- 社区功能下线（2026-10 产品决策：社区整站删除，动态保留并接替其在导航中的位置）
--
-- ⚠️ 破坏性变更：本迁移会永久删除三张社区表及其数据。
--    如需保留历史数据，请在执行前导出：
--      mysqldump -u <user> -p lianyu community_post community_comment community_like > community_backup.sql
--
-- 依据：docs/refactor-2026-10/refactor-plan.md（社区下线属产品变更，非架构重构范围）
-- 关联清理：community.* 两个 RabbitMQ 队列已从 RabbitMqConfig 移除，
--          存量队列消息会随队列删除消失（无消费者，无业务影响）。

DROP TABLE IF EXISTS `community_comment`;
DROP TABLE IF EXISTS `community_like`;
DROP TABLE IF EXISTS `community_post`;

-- 清理已失去落点的社区类站内通知（保留其他类型），避免通知列表出现点不开的死条目
DELETE FROM `user_notification`
WHERE `type` IN ('COMMUNITY_POST_NEW', 'COMMUNITY_LIKE', 'COMMUNITY_COMMENT');

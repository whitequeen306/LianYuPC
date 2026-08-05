-- 拆除平台 DEFAULT 池：对话与内置逻辑（记忆抽取 / 会话摘要 / 群聊@裁决）已全部切换为用户自有文本模型。
-- 可重入：DELETE 幂等，重复执行无副作用。
DELETE FROM api_key_vault WHERE vault_scope = 'DEFAULT' AND provider = 'platform';

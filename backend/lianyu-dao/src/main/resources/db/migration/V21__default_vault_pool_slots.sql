-- 仅在 DEFAULT 池为空时预置 10 个槽位（已有 id 1-10 的旧库会跳过）

INSERT INTO api_key_vault (user_id, provider, vault_scope, enabled, api_key_encrypted, key_version, base_url, model_default, remark)
SELECT NULL, 'platform', 'DEFAULT', 0, 'PENDING_SEED', 'v0', 'https://api.deepseek.com', 'deepseek-v4-flash', CONCAT('pool-slot-', n.n)
FROM (
    SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
    UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
) AS n
WHERE (SELECT COUNT(*) FROM api_key_vault WHERE vault_scope = 'DEFAULT' AND provider = 'platform') = 0;

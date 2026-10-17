"""只读数据访问层：MySQL（aiomysql）+ Redis（redis.asyncio）。

**红线约束（本模块是唯一放行点）**：

1. **只读**：`fetch_all()` 只接受 `SELECT` 开头的语句，任何 DDL/DML 直接抛错。
   这是「Python 不写库」的机制性保证（总纲 D7），不靠人记。
2. **显式超时**：连接、读、Redis socket 全部带超时，禁止零超时（对齐出站 HTTP 红线）。
3. **参数化**：所有语句必须用 `%s` 占位，禁止字符串拼接用户输入。

账号建议：生产请给 agent 配**只读 MySQL 账号**（GRANT SELECT），
本模块的 SELECT 守卫是第二道防线，不是唯一防线。
"""

from __future__ import annotations

import json
import logging
from typing import Any

import aiomysql
import redis.asyncio as aioredis

from app.config import get_settings

logger = logging.getLogger(__name__)


class ReadOnlyViolation(RuntimeError):
    """试图通过本模块执行非 SELECT 语句。"""


_pool: aiomysql.Pool | None = None
_redis: aioredis.Redis | None = None


def _assert_select(sql: str) -> None:
    normalized = " ".join((sql or "").lstrip().split()).lower()
    if not normalized.startswith("select"):
        raise ReadOnlyViolation(f"only SELECT is allowed on the agent read-only pool: {sql[:60]}")


async def pool() -> aiomysql.Pool:
    """惰性创建只读连接池。"""
    global _pool
    if _pool is None:
        settings = get_settings()
        _pool = await aiomysql.create_pool(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_user,
            password=settings.mysql_password,
            db=settings.mysql_database,
            charset="utf8mb4",
            autocommit=True,
            minsize=1,
            maxsize=settings.mysql_pool_size,
            connect_timeout=int(settings.mysql_timeout_seconds),
        )
        logger.info("agent read-only MySQL pool ready: %s:%s/%s",
                    settings.mysql_host, settings.mysql_port, settings.mysql_database)
    return _pool


async def fetch_all(sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    """执行只读查询并返回 dict 列表。"""
    _assert_select(sql)
    connection_pool = await pool()
    async with connection_pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cursor:
            await cursor.execute(sql, params)
            rows = await cursor.fetchall()
            return list(rows or [])


async def redis_client() -> aioredis.Redis:
    """惰性创建 Redis 客户端（读缓存用）。"""
    global _redis
    if _redis is None:
        settings = get_settings()
        _redis = aioredis.from_url(
            settings.redis_url,
            socket_timeout=settings.redis_timeout_seconds,
            socket_connect_timeout=settings.redis_timeout_seconds,
            decode_responses=True,
        )
        logger.info("agent Redis client ready")
    return _redis


async def cache_get_json(key: str, field: str | None = None) -> Any | None:
    """读 JSON 缓存；`field` 非空时按 hash 读。任何失败返回 None（缓存不阻断业务）。"""
    try:
        client = await redis_client()
        raw = await (client.hget(key, field) if field else client.get(key))
        if raw is None:
            return None
        return json.loads(raw)
    except Exception as exc:  # noqa: BLE001 - 缓存失败必须降级
        logger.debug("redis read failed key=%s: %s", key, exc)
        return None


async def cache_put_json(key: str, value: Any, ttl_seconds: float, field: str | None = None) -> None:
    """写 JSON 缓存（TTL 必填）。失败只记 debug。"""
    try:
        client = await redis_client()
        payload = json.dumps(value, ensure_ascii=False)
        if field:
            await client.hset(key, field, payload)
            await client.expire(key, int(ttl_seconds))
        else:
            await client.set(key, payload, ex=int(ttl_seconds))
    except Exception as exc:  # noqa: BLE001
        logger.debug("redis write failed key=%s: %s", key, exc)


async def close() -> None:
    """关闭连接池与 Redis（停机/测试用）。"""
    global _pool, _redis
    if _pool is not None:
        _pool.close()
        await _pool.wait_closed()
        _pool = None
    if _redis is not None:
        await _redis.aclose()
        _redis = None


__all__ = [
    "ReadOnlyViolation",
    "cache_get_json",
    "cache_put_json",
    "close",
    "fetch_all",
    "pool",
    "redis_client",
]

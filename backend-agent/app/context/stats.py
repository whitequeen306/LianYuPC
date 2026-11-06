"""每轮上下文开销的日统计：先能看见，才谈得上调预算。

只写 Redis 的日哈希（`ctx:stats:YYYYMMDD`），不进 MySQL：这些是运维指标，
丢了不心疼；也不该让聊天链路多一次数据库写。
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

from app.db import redis_client

logger = logging.getLogger(__name__)

TTL_SECONDS = 8 * 24 * 3600
FIELDS = ("turns", "est_tokens", "cache_hit", "cache_miss", "trimmed")


def day_key(now: datetime | None = None) -> str:
    moment = now or datetime.now(timezone.utc)
    return f"ctx:stats:{moment.strftime('%Y%m%d')}"


async def record_turn(
    *,
    estimated_tokens: int = 0,
    cache_hit: int | None = None,
    cache_miss: int | None = None,
    trimmed: int = 0,
    turns: int = 1,
) -> None:
    """累加一轮；任何异常都只记 debug——统计不该让回复失败。"""
    if estimated_tokens <= 0 and not cache_hit and not cache_miss and not trimmed and not turns:
        return
    try:
        client = await redis_client()
        key = day_key()
        pipe = client.pipeline()
        if turns:
            pipe.hincrby(key, "turns", turns)
        if estimated_tokens:
            pipe.hincrby(key, "est_tokens", int(estimated_tokens))
        # 0 也要记：命中 0 token 与"拿不到用量"是两件事，分开才看得出问题
        if cache_hit is not None:
            pipe.hincrby(key, "cache_hit", int(cache_hit))
        if cache_miss is not None:
            pipe.hincrby(key, "cache_miss", int(cache_miss))
        if trimmed:
            pipe.hincrby(key, "trimmed", int(trimmed))
        pipe.expire(key, TTL_SECONDS)
        await pipe.execute()
    except Exception as exc:  # noqa: BLE001
        logger.debug("ctx stats write skipped: %s", exc)


def summarize(counts: dict[str, Any]) -> dict[str, Any]:
    """把原始计数换成能看的比率；没有分母时返回 None，不假装是 0。"""
    turns = int(counts.get("turns") or 0)
    hit = int(counts.get("cache_hit") or 0)
    miss = int(counts.get("cache_miss") or 0)
    total = hit + miss
    return {
        "turns": turns,
        "est_tokens_per_turn": round(int(counts.get("est_tokens") or 0) / turns, 1) if turns else None,
        "cache_hit_rate": round(hit / total, 4) if total else None,
        "trimmed_turns": int(counts.get("trimmed") or 0),
    }


async def read_today(now: datetime | None = None) -> dict[str, Any]:
    client = await redis_client()
    raw = await client.hgetall(day_key(now))
    return summarize(raw)

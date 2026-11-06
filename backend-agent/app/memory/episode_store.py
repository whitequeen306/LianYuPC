"""episode 层写入：一次「发生了什么」，带一句原话作为证据。

为什么单独一层：`memory_meta` 存的是事实（她怕打雷），episode 存的是
现场（那天她说「打雷了我睡不着」）。长期记忆的召回质量主要靠后者：
事实可以推断，现场不能编造。

写入仍然只走 `app.write` 的白名单，不直接碰连接池。
"""

from __future__ import annotations

import hashlib
import json
import logging
from datetime import datetime
from typing import Any, Iterable

from app import write

logger = logging.getLogger(__name__)

# MySQL 的 VARCHAR 上限；截断在这里做，避免 pymysql 抛 1406 让整轮抽取失败。
MAX_SUMMARY = 500
MAX_QUOTE = 600
MAX_OPEN_LOOP = 200

_INSERT_SQL = (
    "INSERT INTO memory_episode "
    "(user_id, character_id, conversation_id, scene_json, summary, quote, event_time, "
    " valence, arousal, open_loop, source_msg_ids, content_hash, embedding_state, status) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)"
)


def _clip(text: Any, limit: int) -> str:
    value = "" if text is None else str(text).strip()
    return value[:limit]


def _clamp(value: Any, low: float, high: float) -> float | None:
    if value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return max(low, min(high, number))


def episode_hash(*, user_id: int, character_id: int, quote: str, event_time: datetime | None) -> str:
    """同一个人、同一个角色、同一句原话、同一个时间点 = 同一条 episode。"""
    stamp = event_time.isoformat() if event_time else ""
    raw = f"{user_id}\u0001{character_id}\u0001{quote}\u0001{stamp}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def build_insert(
    *,
    user_id: int,
    character_id: int,
    summary: str,
    quote: str,
    conversation_id: int | None = None,
    scene: dict[str, Any] | None = None,
    event_time: datetime | None = None,
    valence: float | None = None,
    arousal: float | None = None,
    open_loop: str | None = None,
    source_msg_ids: Iterable[int] | None = None,
) -> tuple[str, tuple[Any, ...]]:
    """构造参数化 INSERT。抽出来是为了让 SQL 形状可测（不需要真库）。"""
    clean_quote = _clip(quote, MAX_QUOTE)
    clean_summary = _clip(summary or clean_quote, MAX_SUMMARY)
    digest = episode_hash(
        user_id=user_id, character_id=character_id, quote=clean_quote, event_time=event_time,
    )
    params = (
        int(user_id),
        int(character_id),
        int(conversation_id) if conversation_id else None,
        json.dumps(scene, ensure_ascii=False) if scene else None,
        clean_summary,
        clean_quote,
        event_time,
        _clamp(valence, -1.0, 1.0),
        _clamp(arousal, 0.0, 1.0),
        _clip(open_loop, MAX_OPEN_LOOP) or None,
        json.dumps([int(x) for x in (source_msg_ids or [])]) or None,
        digest,
        "pending",
        "active",
    )
    return _INSERT_SQL, params


async def insert_episode(**kwargs: Any) -> int:
    """写入一条 episode，返回新行 id；重复（同 hash）返回 0。

    重复不是错误：抽取可能因为重试跑两遍，幂等比报错更省事。
    """
    sql, params = build_insert(**kwargs)
    try:
        async with write.transaction() as cursor:
            return await write.insert_returning_id(cursor, sql, params)
    except Exception as exc:  # noqa: BLE001 - 唯一键冲突按幂等处理，其它照常抛出
        if "Duplicate entry" in str(exc):
            logger.debug("episode already stored: %s", params[11])
            return 0
        raise


__all__ = ["build_insert", "episode_hash", "insert_episode"]

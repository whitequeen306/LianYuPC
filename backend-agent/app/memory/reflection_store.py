"""把一幕结束时的 impression 写成 reflection 层记忆。

为什么单独一层：episode 记「发生了什么」，fact 记「她是谁」，reflection 记
「我怎么想这件事」——它允许角色对同一段经历有立场，也允许被后来的自己推翻。
所以 reflection 走 `memory_meta`（可 supersede），tau 180 天，不参与画像槽位。
"""

from __future__ import annotations

import hashlib
import json
import logging
from typing import Any, Iterable

from app.db import fetch_all
from app.memory.outbox import enqueue_upsert
from app.write import insert_returning_id, transaction

logger = logging.getLogger(__name__)

REFLECTION_TAU_DAYS = 180
REFLECTION_IMPORTANCE = 0.65
MAX_SUMMARY_CHARS = 120
DEDUPE_DAYS = 7


def build_summary(impression: str) -> str:
    return f"【印象】{(impression or '').strip()}"[:MAX_SUMMARY_CHARS]


def content_hash(user_id: int, character_id: int, summary: str) -> str:
    raw = f"{user_id}\u0001{character_id}\u0001{summary}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


async def insert_reflection(
    *,
    user_id: int,
    character_id: int,
    conversation_id: int | None,
    impression: str,
    scene: dict[str, Any] | None = None,
    source_msg_ids: Iterable[int] = (),
) -> int:
    """写一条 reflection；同角色同一天同一句话只留一条（模型偶尔会重复）。"""
    summary = build_summary(impression)
    if len(summary) <= len("【印象】") + 4:
        return 0

    try:
        rows = await fetch_all(
            "SELECT id FROM memory_meta WHERE user_id = %s AND character_id = %s "
            "AND layer = 'reflection' AND summary = %s "
            "AND created_at > NOW() - INTERVAL 7 DAY LIMIT 1",
            (user_id, character_id, summary),
        )
    except Exception as exc:  # noqa: BLE001 - 去重失败不阻断写入
        logger.warning("reflection dedupe failed: %s", exc)
        rows = []
    if rows:
        return 0

    digest = content_hash(user_id, character_id, summary)
    try:
        async with transaction() as cur:
            memory_id = await insert_returning_id(
                cur,
                "INSERT INTO memory_meta (character_id, user_id, summary, memory_type, importance, "
                "layer, strength, tau_days, status, fade_level, source_msg_ids, content_hash, quote, "
                "created_at, event_time, valid_from, last_decay_at, revision) "
                "VALUES (%s, %s, %s, 'EMOTION', %s, 'reflection', 1.000, %s, 'active', 'active', "
                "%s, %s, %s, NOW(), NOW(), NOW(), NOW(), 1)",
                (
                    character_id,
                    user_id,
                    summary,
                    REFLECTION_IMPORTANCE,
                    REFLECTION_TAU_DAYS,
                    json.dumps(list(source_msg_ids)),
                    digest,
                    (impression or "").strip()[:600],
                ),
            )
            # 印象也要能被向量召回：不入队就永远进不了 v3，且 outbox 计数看着还是 0。
            if memory_id:
                await enqueue_upsert(cur, memory_id)
            return memory_id
    except Exception as exc:  # noqa: BLE001 - 印象写不进去不影响这一轮回复
        logger.warning("reflection insert failed conv=%s: %s", conversation_id, exc)
        return 0


__all__ = ["MAX_SUMMARY_CHARS", "REFLECTION_TAU_DAYS", "build_summary", "content_hash",
           "insert_reflection"]

"""主动浮现：让一条"快要沉下去、但还没凉透"的记忆被自然提起。

三条硬约束（少一条就会变成骚扰或复读）：

1. **频控**：每角色每自然周最多一次（Redis `resurface:{cid}:{iso year-week}`）。
2. **只挑温的**：强度 0.35–0.55，或正好是周年；太新的没意义，太旧的提起来像翻旧账。
3. **贴题**：有场景话题时，话题与记忆的余弦相似度 ≥0.2 才开口；不贴题就这周先不提。

挑选是纯函数（`pick`），副作用只有"记下这周提过"——方便用固定行做回归。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Iterable

logger = logging.getLogger(__name__)

MIN_STRENGTH = 0.35
MAX_STRENGTH = 0.55
IDLE_DAYS = 30
ANNIVERSARY_MIN_DAYS = 300
WEEKLY_CAP_SECONDS = 7 * 24 * 3600
TOPIC_MIN_SIMILARITY = 0.2

_CANDIDATE_SQL = (
    "SELECT id, summary, importance, strength, fade_level, status, pinned, muted, "
    "event_time, last_recalled_at "
    "FROM memory_meta "
    "WHERE character_id = %s AND user_id = %s AND status = 'active' "
    "AND muted = 0 AND pinned = 0 "
    "AND ("
    "  (DATE_FORMAT(event_time, '%%m-%%d') = DATE_FORMAT(NOW(), '%%m-%%d') "
    "   AND event_time < NOW() - INTERVAL %s DAY)"
    "  OR (strength BETWEEN %s AND %s "
    "      AND (last_recalled_at IS NULL OR last_recalled_at < NOW() - INTERVAL %s DAY))"
    ") "
    "ORDER BY strength DESC, importance DESC LIMIT 12"
)


def week_key(character_id: int, now: datetime) -> str:
    year, week, _ = now.isocalendar()
    return f"resurface:{int(character_id)}:{year}-{week:02d}"


def is_anniversary(event_time: Any, now: datetime) -> bool:
    """同月同日、且至少一年前——差一天都不算，宁可不提。"""
    if not isinstance(event_time, datetime):
        return False
    return (
        event_time.month == now.month
        and event_time.day == now.day
        and (now - event_time).days >= ANNIVERSARY_MIN_DAYS
    )


def in_band(row: dict[str, Any]) -> bool:
    try:
        strength = float(row.get("strength"))
    except (TypeError, ValueError):
        return False
    return MIN_STRENGTH <= strength <= MAX_STRENGTH


def pick(
    rows: Iterable[dict[str, Any]],
    now: datetime,
    *,
    topic_similarity: dict[int, float] | None = None,
) -> dict[str, Any] | None:
    """从候选里挑一条；周年优先，其次强度更接近 0.45 的（最好"想起来"的那种）。"""
    rows = [row for row in rows or [] if row.get("summary")]
    anniversaries = [row for row in rows if is_anniversary(row.get("event_time"), now)]
    warm = [row for row in rows if in_band(row)]
    pool = anniversaries or warm
    if not pool:
        return None

    if topic_similarity is not None:
        pool = [row for row in pool
                if topic_similarity.get(int(row["id"]), 0.0) >= TOPIC_MIN_SIMILARITY]
        if not pool:
            return None

    if anniversaries:
        pool = sorted(pool, key=lambda row: row.get("event_time") or now)
    else:
        pool = sorted(pool, key=lambda row: abs(float(row.get("strength") or 0) - 0.45))
    chosen = dict(pool[0])
    chosen["reason"] = (
        "anniversary" if is_anniversary(chosen.get("event_time"), now) else "fading"
    )
    return chosen


async def _topic_similarity(rows: list[dict[str, Any]], topic: str) -> dict[int, float]:
    from app.memory.embedding import embed

    vectors = await embed([topic, *[str(row["summary"]) for row in rows]])
    if not vectors or len(vectors) != len(rows) + 1:
        return {}
    base, rest = vectors[0], vectors[1:]

    def cosine(a: list[float], b: list[float]) -> float:
        dot = sum(x * y for x, y in zip(a, b))
        na = sum(x * x for x in a) ** 0.5
        nb = sum(y * y for y in b) ** 0.5
        return dot / (na * nb) if na and nb else 0.0

    return {int(row["id"]): cosine(base, vector) for row, vector in zip(rows, rest)}


async def suggest(
    character_id: int,
    user_id: int,
    *,
    topic: str | None = None,
    now: datetime | None = None,
) -> dict[str, Any] | None:
    """这周该不该主动提一句；提了就占掉本周名额。"""
    from app.db import fetch_all, redis_client

    now = now or datetime.now()
    key = week_key(character_id, now)
    try:
        redis = await redis_client()
        claimed = await redis.set(key, "1", ex=WEEKLY_CAP_SECONDS, nx=True)
        if not claimed:
            return None
    except Exception as exc:  # noqa: BLE001 - Redis 挂了宁可这周不提，也不连发
        logger.warning("resurface skipped (redis): %s", exc)
        return None

    try:
        rows = await fetch_all(
            _CANDIDATE_SQL,
            (int(character_id), int(user_id), ANNIVERSARY_MIN_DAYS,
             MIN_STRENGTH, MAX_STRENGTH, IDLE_DAYS),
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("resurface candidates failed: %s", exc)
        return None

    similarity = None
    if topic and rows:
        try:
            similarity = await _topic_similarity(rows, topic)
        except Exception as exc:  # noqa: BLE001 - 算不出相似度就不挑，避免乱提
            logger.warning("resurface similarity failed: %s", exc)
            return None

    return pick(rows, now, topic_similarity=similarity)


__all__ = ["in_band", "is_anniversary", "pick", "suggest", "week_key"]

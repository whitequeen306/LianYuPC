"""跨天整合：把一周的 episode 收成一条周记（reflection 层）。

为什么先做「抽取式」：夜间任务手里没有用户请求里那份模型凭据，硬要塞一次
模型调用就得把凭据常驻到服务端。先用「按时间挑几条、连成一段」的无模型
版本；等常驻凭据落位再换生成式。周记仍然可衰减、可 supersede、可在记忆页
看到，只是文风更朴素。

按自然周对齐（周一 00:00 起算），同一周只写一条：content_hash 里带周标签，
重复跑不会叠出一堆同周周记。
"""

from __future__ import annotations

import asyncio
import hashlib
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Sequence

from app.db import fetch_all
from app.memory.outbox import enqueue_upsert
from app.write import insert_returning_id, transaction

WEEK_DAYS = 7
MIN_EPISODES = 3
MAX_ITEMS = 4
MAX_DIGEST_CHARS = 240
DIGEST_TAU_DAYS = 180
DIGEST_IMPORTANCE = 0.70
MAX_ITEM_CHARS = 48


def week_start(now: datetime) -> datetime:
    """本周一 00:00（UTC，与库里 NOW() 同一口径）。"""
    midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    return midnight - timedelta(days=midnight.weekday())


def week_label(start: datetime, now: datetime) -> str:
    """08-11～08-17 这种人能读的区间；本周还没过完就标「至今」。"""
    end = start + timedelta(days=WEEK_DAYS - 1)
    tail = "至今" if now < end + timedelta(days=1) else end.strftime("%m-%d")
    return f"{start.strftime('%m-%d')}～{tail}"


def build_digest(episodes: Sequence[dict[str, Any]], start: datetime, now: datetime) -> str:
    """把一周里最能代表这段时间的几条 episode 连成一段。

    排序用「情绪强度 × |效价| + 时间新鲜度」——一周里最值得记住的往往不是
    最早发生的，而是情绪最重的那几件。摘要本身已经很短，这里不再压缩。
    """
    ranked = sorted(
        (e for e in episodes if str(e.get("summary") or "").strip()),
        key=lambda e: (
            float(e.get("arousal") or 0) * (0.5 + abs(float(e.get("valence") or 0))),
            _as_datetime(e.get("event_time")) or start,
        ),
        reverse=True,
    )
    picked = sorted(
        ranked[:MAX_ITEMS], key=lambda e: _as_datetime(e.get("event_time")) or start
    )
    body = "；".join(_clip(str(e["summary"]).strip(), MAX_ITEM_CHARS) for e in picked)
    digest = f"【周记】{week_label(start, now)}：{body}"
    return _clip(digest, MAX_DIGEST_CHARS)


def digest_hash(user_id: int, character_id: int, start: datetime) -> str:
    """同一角色的同一周只认一个 hash，重复跑不会写第二条。"""
    raw = f"{user_id}\u0001{character_id}\u0001week\u0001{start.date().isoformat()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def group_by_owner(rows: Iterable[dict[str, Any]]) -> dict[tuple[int, int], list[dict[str, Any]]]:
    grouped: dict[tuple[int, int], list[dict[str, Any]]] = {}
    for row in rows:
        key = (int(row["user_id"]), int(row["character_id"]))
        grouped.setdefault(key, []).append(row)
    return grouped


async def load_week_episodes(start: datetime) -> list[dict[str, Any]]:
    return await fetch_all(
        "SELECT id, user_id, character_id, summary, quote, event_time, valence, arousal "
        "FROM memory_episode WHERE status = 'active' AND event_time >= %s "
        "ORDER BY event_time ASC",
        (start,),
    )


async def _already_written(cur, content_hash: str) -> bool:
    await cur.execute(
        "SELECT id FROM memory_meta WHERE layer = 'reflection' AND content_hash = %s LIMIT 1",
        (content_hash,),
    )
    return await cur.fetchone() is not None


async def write_digest(
    cur, *, user_id: int, character_id: int, summary: str, content_hash: str,
    episode_ids: Sequence[int], start: datetime,
) -> int:
    memory_id = await insert_returning_id(
        cur,
        "INSERT INTO memory_meta (character_id, user_id, summary, memory_type, importance, "
        "layer, strength, tau_days, status, fade_level, source_msg_ids, content_hash, "
        "event_time, created_at, valid_from, last_decay_at, revision) "
        "VALUES (%s, %s, %s, 'RELATION', %s, 'reflection', 1.000, %s, 'active', 'active', "
        "%s, %s, %s, NOW(), NOW(), NOW(), 1)",
        (
            character_id,
            user_id,
            summary,
            DIGEST_IMPORTANCE,
            DIGEST_TAU_DAYS,
            _json_ids(episode_ids),
            content_hash,
            start,
        ),
    )
    # 周记同样要进向量库：它是跨天回忆的锚点，漏投影等于"这周发生过什么"检索不到。
    if memory_id:
        await enqueue_upsert(cur, memory_id)
    return memory_id


async def consolidate(now: datetime | None = None) -> int:
    """扫本周 episode，按角色收周记；返回写入条数。"""
    now = now or datetime.now(timezone.utc).replace(tzinfo=None)
    start = week_start(now)
    grouped = group_by_owner(await load_week_episodes(start))
    written = 0
    for (user_id, character_id), episodes in grouped.items():
        if len(episodes) < MIN_EPISODES:
            continue
        summary = build_digest(episodes, start, now)
        content_hash = digest_hash(user_id, character_id, start)
        async with transaction() as cur:
            if await _already_written(cur, content_hash):
                continue
            await write_digest(
                cur,
                user_id=user_id,
                character_id=character_id,
                summary=summary,
                content_hash=content_hash,
                episode_ids=[int(e["id"]) for e in episodes][:20],
                start=start,
            )
            written += 1
    return written


def main() -> None:
    print(f"consolidated {asyncio.run(consolidate())} week(s)")


def _as_datetime(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value.replace(tzinfo=None)
    return None


def _clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _json_ids(ids: Sequence[int]) -> str:
    import json

    return json.dumps(list(ids), ensure_ascii=False)


if __name__ == "__main__":
    main()

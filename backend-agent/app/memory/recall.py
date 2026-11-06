"""v3 召回：把候选行取出来、打分、去重，交给渲染层。

与 `data_readers.py` 的老路径（画像槽位 + importance top20 + LIKE 5）并列存在，
由 `settings.memory_recall_v3_enabled` 切换。为什么要开关而不是直接替换：
它依赖 V58 的新列与 ngram 索引，灰发前旧库还没迁移完。

排序不在这里"算完就信"：先取 60 条候选（按全文相关度/重要度），再用
`app.memory.retrieve` 打分、去冷却、MMR 去重 —— 数据库只负责把可疑的捞全。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from app.data_readers import fetch_all
from app.memory import retrieve

# 候选上限：多取一些再排序，避免"数据库 top5"把真正相关的挤掉。
CANDIDATE_LIMIT = 60
# episode 的默认衰减常数（天）。情绪唤醒越高越难忘，抽取时已写进 arousal，
# 这里先用中位值；P3 再把 arousal 映射成 tau。
EPISODE_TAU_DAYS = 30

_CANDIDATE_SQL = """
SELECT id, summary, memory_type, importance, strength, tau_days, fade_level,
       last_decay_at, last_injected_at, event_time, created_at, quote,
       MATCH(summary) AGAINST (%s) AS rel
FROM memory_meta
WHERE character_id = %s AND user_id = %s
  AND status = 'active'
  AND (valid_to IS NULL OR valid_to > NOW())
  AND muted = 0
ORDER BY rel DESC, importance DESC, created_at DESC
LIMIT %s
"""


_CORE_SQL = """
SELECT id, summary, memory_type, importance, strength, tau_days, fade_level,
       last_decay_at, last_injected_at, event_time, created_at, quote, layer, pinned,
       1.0 AS rel
FROM memory_meta
WHERE character_id = %s AND user_id = %s
  AND status = 'active'
  AND (layer = 'core' OR pinned = 1)
LIMIT 12
"""


_EPISODE_SQL = """
SELECT CONCAT('ep-', id) AS id, summary, 'EPISODE' AS memory_type,
       0.7 AS importance, 1.0 AS strength, %s AS tau_days,
       NULL AS fade_level, NULL AS last_decay_at, NULL AS last_injected_at,
       event_time, created_at, quote,
       MATCH(summary) AGAINST (%s) AS rel
FROM memory_episode
WHERE character_id = %s AND user_id = %s AND status = 'active'
ORDER BY rel DESC, event_time DESC
LIMIT %s
"""


async def load_candidates(
    character_id: int,
    user_id: int,
    query: str | None,
    *,
    limit: int = CANDIDATE_LIMIT,
) -> list[dict[str, Any]]:
    rows = await fetch_all(
        _CANDIDATE_SQL,
        ((query or "").strip(), character_id, user_id, limit),
    )
    # core / pinned 是"设定"，不该因为与当前话题不相关就被挤掉。
    core = await fetch_all(_CORE_SQL, (character_id, user_id))
    seen = {row.get("id") for row in core}
    merged = [*core, *[row for row in rows if row.get("id") not in seen]]

    # 现场（episode）：事实能推断，原话不能。它只占候选的一半上限，
    # 避免几条生动的原话把"设定"挤出去。
    try:
        episodes = await fetch_all(
            _EPISODE_SQL,
            (EPISODE_TAU_DAYS, (query or "").strip(), character_id, user_id, max(1, limit // 2)),
        )
    except Exception:  # noqa: BLE001 - 老库没 V58 时 episode 表不存在，降级即可
        episodes = []
    return [*merged, *[row for row in (episodes or []) if row.get("id") not in seen]]


def rank_rows(
    rows: list[dict[str, Any]],
    *,
    limit: int = retrieve.DEFAULT_RECALL_LIMIT,
    now: datetime | None = None,
) -> list[dict[str, Any]]:
    """打分 + 冷却 + MMR。纯函数，便于用固定行做回归。"""
    if not rows:
        return []

    max_rel = max((float(row.get("rel") or 0.0) for row in rows), default=0.0) or 1.0
    scored: list[dict[str, Any]] = []
    for row in rows:
        if retrieve.in_cooldown(row, now):
            continue
        core = str(row.get("layer") or "") == "core" or bool(row.get("pinned"))
        scored.append({
            **row,
            "score": retrieve.score_candidate(
                row,
                relevance=1.0 if core else float(row.get("rel") or 0.0) / max_rel,
                now=now,
            ),
        })

    scored.sort(key=lambda row: row["score"], reverse=True)
    return retrieve.mmr_select(scored, limit=limit)


async def recall(
    character_id: int,
    user_id: int,
    query: str | None,
    *,
    limit: int = retrieve.DEFAULT_RECALL_LIMIT,
    now: datetime | None = None,
) -> list[dict[str, Any]]:
    rows = await load_candidates(character_id, user_id, query)
    return rank_rows(rows, limit=limit, now=now)


def render_rows(rows: list[dict[str, Any]], now: datetime | None = None) -> str | None:
    """按类型分组渲染（与旧块同样的标题），行内用原话 + 置信标记。"""
    from app.data_readers import TYPE_LABELS

    parts: list[str] = []
    for memory_type, label in TYPE_LABELS:
        seen: set[str] = set()
        lines: list[str] = []
        for row in rows:
            if str(row.get("memory_type") or "").upper() != memory_type:
                continue
            text = str(row.get("quote") or row.get("summary") or "").strip()
            if not text or text in seen:
                continue
            seen.add(text)
            mark = retrieve.confidence_mark(row, now)
            date = (row.get("event_time") or row.get("created_at"))
            prefix = f"[{date:%m-%d}·{mark}] " if isinstance(date, datetime) else f"[{mark}] "
            lines.append("- " + prefix + text)
        if lines:
            parts.append("[" + label + "]\n" + "\n".join(lines))
    return "\n\n".join(parts) if parts else None


__all__ = ["CANDIDATE_LIMIT", "load_candidates", "rank_rows", "recall", "render_rows"]

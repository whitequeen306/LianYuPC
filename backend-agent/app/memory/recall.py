"""v3 召回：把候选行取出来、打分、去重，交给渲染层。

与 `data_readers.py` 的老路径（画像槽位 + importance top20 + LIKE 5）并列存在，
由 `settings.memory_recall_v3_enabled` 切换。为什么要开关而不是直接替换：
它依赖 V58 的新列与 ngram 索引，灰发前旧库还没迁移完。

排序不在这里"算完就信"：先取 60 条候选（按全文相关度/重要度），再用
`app.memory.retrieve` 打分、去冷却、MMR 去重 —— 数据库只负责把可疑的捞全。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from app import write
from app.config import get_settings
from app.data_readers import fetch_all

logger = logging.getLogger(__name__)
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
  AND muted = 0
  AND (layer = 'core' OR pinned = 1)
LIMIT 12
"""


_EPISODE_SQL = """
SELECT CONCAT('ep-', id) AS id, summary, 'EPISODE' AS memory_type,
       0.7 AS importance, 1.0 AS strength, %s AS tau_days,
       NULL AS fade_level, event_time AS last_decay_at, last_injected_at,
       event_time, created_at, quote,
       MATCH(summary) AGAINST (%s) AS rel
FROM memory_episode
WHERE character_id = %s AND user_id = %s AND status = 'active'
ORDER BY rel DESC, event_time DESC
LIMIT %s
"""


_VECTOR_SQL_TEMPLATE = """
SELECT id, summary, memory_type, importance, strength, tau_days, fade_level,
       last_decay_at, last_injected_at, event_time, created_at, quote, layer, pinned,
       1.0 AS rel
FROM memory_meta
WHERE id IN ({placeholders})
  AND status = 'active'
  AND (valid_to IS NULL OR valid_to > NOW())
  AND muted = 0
"""


async def _vector_rows(
    character_id: int,
    user_id: int,
    query: str | None,
    *,
    top_k: int | None = None,
) -> list[dict[str, Any]]:
    """向量路：Milvus 出 id 与相似度，回 MySQL 取权威字段。

    为什么不直接用 Milvus 里的 summary：向量库是投影不是事实源。摘要可能已被
    supersede 或改写，直接渲染会把旧名字当新名字用。取不到/没开就返回空，
    让 ngram 候选照常工作 —— 向量是加分项，不是单点依赖。
    """
    settings = get_settings()
    if not settings.memory_vector_recall_enabled:
        return []
    text = (query or "").strip()
    if not text:
        return []
    try:
        from app import milvus_store
        from app.memory.embedding import embed

        vectors = await embed([text])
        if not vectors:
            return []
        hits = milvus_store.search_hits(
            character_id, user_id, vectors[0],
            top_k=top_k or settings.memory_vector_recall_top_k,
        )
    except Exception as exc:  # noqa: BLE001 - 向量路失败不影响关键词召回
        logger.warning("vector recall skipped: %s", exc)
        return []
    if not hits:
        return []

    scores: dict[int, float] = {}
    for memory_id, score in hits:
        key = int(memory_id)
        scores[key] = max(float(score), scores.get(key, -1.0))
    placeholders = ", ".join(["%s"] * len(scores))
    try:
        rows = await fetch_all(
            _VECTOR_SQL_TEMPLATE.format(placeholders=placeholders),
            tuple(scores),
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("vector rows fetch failed: %s", exc)
        return []
    for row in rows or []:
        hit = scores.get(int(row.get("id") or 0))
        if hit is not None:
            row["rel"] = max(float(row.get("rel") or 0.0), hit)
    return list(rows or [])


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
    # 每个候选都带 lane：ngram 的 MATCH 分数与向量的 cosine 不可比，
    # 必须先按各自池子的最大值归一，再进同一个打分器（否则余弦 0.8 会被
    # 当成 0.8/20 的 4 分，向量路等于没开）。
    rows = [{**row, "_lane": "ngram"} for row in rows or []]
    # core / pinned 是"设定"，不该因为与当前话题不相关就被挤掉。
    core = await fetch_all(_CORE_SQL, (character_id, user_id))
    seen = {row.get("id") for row in core}
    merged = [{**row, "_lane": "core"} for row in core]
    merged += [row for row in rows if row.get("id") not in seen]

    # 现场（episode）：事实能推断，原话不能。它只占候选的一半上限，
    # 避免几条生动的原话把"设定"挤出去。
    try:
        episodes = await fetch_all(
            _EPISODE_SQL,
            (EPISODE_TAU_DAYS, (query or "").strip(), character_id, user_id, max(1, limit // 2)),
        )
    except Exception as exc:  # noqa: BLE001 - 老库没 V58/V59 时降级，但要留下线索
        logger.warning("memory episode lane failed (migration V58/V59?): %s", exc)
        episodes = []
    episodes = [{**row, "_lane": "episode"} for row in (episodes or []) if row.get("id") not in seen]
    seen.update(row.get("id") for row in episodes)

    vector_rows = [{**row, "_lane": "vector"} for row in await _vector_rows(character_id, user_id, query, top_k=limit)]
    return [*merged, *episodes, *[row for row in vector_rows if row.get("id") not in seen]]


async def mark_injected(rows: list[dict[str, Any]]) -> int:
    """记下"这一轮真的把哪几条给了模型"。

    冷却（3 天不再重复注入）与"多久没被想起"都依赖这个时间戳；没有它，
    `in_cooldown` 永远为假，同一句设定会每轮重复出现。episode 的 id 形如
    `ep-9`，要回到 `memory_episode` 记（V59 才有的列），否则同一段原话会
    每轮都冒出来。
    """
    meta_ids: list[int] = []
    episode_ids: list[int] = []
    for row in rows or []:
        raw = str(row.get("id") or "")
        if raw.startswith("ep-"):
            try:
                episode_ids.append(int(raw[3:]))
            except ValueError:
                continue
            continue
        try:
            meta_ids.append(int(raw))
        except ValueError:
            continue
    if not meta_ids and not episode_ids:
        return 0

    touched = 0
    async with write.transaction() as cur:
        if meta_ids:
            placeholders = ", ".join(["%s"] * len(meta_ids))
            touched += await write.execute_write(
                cur,
                "UPDATE memory_meta SET last_injected_at = NOW(), last_recalled_at = NOW(), "
                f"recall_count = recall_count + 1 WHERE id IN ({placeholders})",
                tuple(meta_ids),
            )
        if episode_ids:
            placeholders = ", ".join(["%s"] * len(episode_ids))
            touched += await write.execute_write(
                cur,
                "UPDATE memory_episode SET last_injected_at = NOW() "
                f"WHERE id IN ({placeholders})",
                tuple(episode_ids),
            )
    return touched


def rank_rows(
    rows: list[dict[str, Any]],
    *,
    limit: int = retrieve.DEFAULT_RECALL_LIMIT,
    now: datetime | None = None,
) -> list[dict[str, Any]]:
    """打分 + 冷却 + MMR。纯函数，便于用固定行做回归。"""
    if not rows:
        return []

    lane_max: dict[str, float] = {}
    for row in rows:
        lane = str(row.get("_lane") or "ngram")
        lane_max[lane] = max(lane_max.get(lane, 0.0), float(row.get("rel") or 0.0))
    scored: list[dict[str, Any]] = []
    for row in rows:
        core = str(row.get("layer") or "") == "core" or bool(row.get("pinned"))
        # 设定与钉住项不受冷却约束：它们是"她是谁"，不该因为刚提过就消失。
        if not core and retrieve.in_cooldown(row, now):
            continue
        lane = str(row.get("_lane") or "ngram")
        raw = float(row.get("rel") or 0.0)
        relevance = 1.0 if core or lane_max.get(lane, 0.0) <= 0 else raw / lane_max[lane]
        scored.append({
            **row,
            "score": retrieve.score_candidate(row, relevance=relevance, now=now),
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


def render_rows_limited(rows: list[dict[str, Any]], now: datetime | None = None,
                        max_tokens: int = 400) -> tuple[str | None, list[Any]]:
    """按类型分组渲染，**整行**地卡预算，并返回真正进了块的行 id。

    之前是先渲染再按字符截断：尾巴上被截掉半截的行也会被记成「已经注入过」，
    冷却期里它反而再也不出现。宁可不写半行，也不能把没看见的算成看过。
    """
    from app.context.tokens import estimate_tokens
    from app.data_readers import TYPE_LABELS

    parts: list[str] = []
    used: list[Any] = []
    budget = max(0, max_tokens)
    spent = 0
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
            line = "- " + prefix + text
            cost = estimate_tokens(line) + 1
            if spent + cost > budget:
                break
            spent += cost
            lines.append(line)
            used.append(row.get("id"))
        if lines:
            parts.append("[" + label + "]\n" + "\n".join(lines))
    return ("\n\n".join(parts) if parts else None), used


def render_rows(rows: list[dict[str, Any]], now: datetime | None = None) -> str | None:
    """按类型分组渲染（与旧块同样的标题），行内用原话 + 置信标记。"""
    return render_rows_limited(rows, now)[0]


__all__ = ["CANDIDATE_LIMIT", "load_candidates", "rank_rows", "recall", "render_rows",
           "render_rows_limited"]

"""v3 召回：把候选行取出来、打分、去重，交给渲染层。

与 `data_readers.py` 的老路径（画像槽位 + importance top20 + LIKE 5）并列存在，
由 `settings.memory_recall_v3_enabled` 切换。为什么要开关而不是直接替换：
它依赖 V58 的新列与 ngram 索引，灰发前旧库还没迁移完。

排序不在这里"算完就信"：先取 60 条候选（按全文相关度/重要度），再用
`app.memory.retrieve` 打分、去冷却、MMR 去重 —— 数据库只负责把可疑的捞全。
"""

from __future__ import annotations

import asyncio
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
# episode 的默认衰减常数（天）。原话回答的是“那天到底怎么说的”，
# 不该比一条事实更快消失；30 天会让一年前的强匹配还排在十几名。
EPISODE_TAU_DAYS = 180

_CANDIDATE_SQL = """
SELECT id, summary, memory_type, importance, strength, tau_days, fade_level,
       last_decay_at, last_injected_at, event_time, created_at, quote,
       MATCH(summary) AGAINST (%s) AS rel
FROM memory_meta
WHERE character_id = %s AND user_id = %s
  AND status = 'active'
  AND (valid_to IS NULL OR valid_to > NOW())
  AND muted = 0
  AND pinned = 0
  AND layer <> 'core'
  AND (fade_level IS NULL OR fade_level <> 'archived')
ORDER BY rel DESC, importance DESC, created_at DESC
LIMIT %s
"""

# 有话题时只看"真的沾边"的行：MATCH 为 0 的候选只是按重要度排进来的，
# 在真机上会把"她养了一只猫"塞进「加班」的召回块（rel=0 仍能拿到 0.44 分）。
_CANDIDATE_SQL_RELEVANT = _CANDIDATE_SQL.replace(
    "ORDER BY rel DESC, importance DESC, created_at DESC",
    "HAVING rel > 0\nORDER BY rel DESC, importance DESC, created_at DESC",
)


_CORE_SQL = """
SELECT id, summary, memory_type, importance, strength, tau_days, fade_level,
       last_decay_at, last_injected_at, event_time, created_at, quote, layer, pinned,
       1.0 AS rel
FROM memory_meta
WHERE character_id = %s AND user_id = %s
  AND status = 'active'
  AND muted = 0
  AND (layer = 'core' OR pinned = 1)
ORDER BY pinned DESC, importance DESC, created_at DESC
LIMIT 12
"""


_EPISODE_SQL = """
SELECT CONCAT('ep-', id) AS id, summary, 'EPISODE' AS memory_type,
       0.7 AS importance, 1.0 AS strength, %s AS tau_days,
       NULL AS fade_level, event_time AS last_decay_at, last_injected_at,
       event_time, created_at, quote,
       GREATEST(MATCH(summary) AGAINST (%s),
                IF(summary LIKE CONCAT('%%', %s, '%%'), 0.05, 0)) AS rel
FROM memory_episode
WHERE character_id = %s AND user_id = %s AND status = 'active'
ORDER BY rel DESC, event_time DESC
LIMIT %s
"""

_EPISODE_SQL_RELEVANT = _EPISODE_SQL.replace(
    "ORDER BY rel DESC, event_time DESC",
    "HAVING rel > 0\nORDER BY rel DESC, event_time DESC",
)


_EPISODE_VECTOR_SQL = """
SELECT id, summary, 'EPISODE' AS memory_type, 0.7 AS importance, 1.0 AS strength,
       %s AS tau_days, NULL AS fade_level, event_time AS last_decay_at,
       last_injected_at, event_time, created_at, quote, 'episode' AS layer, 0 AS pinned,
       0.0 AS rel
FROM memory_episode
WHERE id IN ({placeholders})
  AND status = 'active'
"""


_VECTOR_SQL_TEMPLATE = """
SELECT id, summary, memory_type, importance, strength, tau_days, fade_level,
       last_decay_at, last_injected_at, event_time, created_at, quote, layer, pinned,
       0.0 AS rel
FROM memory_meta
WHERE id IN ({placeholders})
  AND status = 'active'
  AND (valid_to IS NULL OR valid_to > NOW())
  AND muted = 0
  AND pinned = 0
  AND layer <> 'core'
  AND (fade_level IS NULL OR fade_level <> 'archived')
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
    # 没有可用的向量化能力时别去试：外网后端没 key 会等满 HTTP 超时，一次召回能把
    # 回复拖慢好几秒。本地后端只看模型文件在不在。
    from app.memory.embedding import is_configured

    if not is_configured():
        return []
    text = (query or "").strip()
    if not text:
        return []
    try:
        from app import milvus_store
        from app.memory.embedding import embed_query

        vector = await embed_query(text)
        if not vector:
            return []
        # getattr：测试替身与旧配置里没有这个字段，缺省按 v2 处理
        search = (
            milvus_store.search_hits_v3
            if getattr(settings, "memory_vectors_v3_enabled", False)
            else milvus_store.search_hits
        )
        hits = search(
            character_id, user_id, vector,
            top_k=top_k or settings.memory_vector_recall_top_k,
        )
    except Exception as exc:  # noqa: BLE001 - 向量路失败不影响关键词召回
        logger.warning("vector recall skipped: %s", exc)
        return []
    if not hits:
        return []

    from app import milvus_store
    from app.memory.embedding import min_similarity

    offset = int(getattr(milvus_store, "EPISODE_ID_OFFSET", 0))
    floor = min_similarity()
    scores: dict[int, float] = {}
    episode_scores: dict[int, float] = {}
    for memory_id, score in hits:
        if float(score) < floor:
            continue
        key = int(memory_id)
        bucket = episode_scores if offset and key >= offset else scores
        bucket[key] = max(float(score), bucket.get(key, -1.0))
    if not scores and not episode_scores:
        return []

    rows: list[dict[str, Any]] = []
    if scores:
        placeholders = ", ".join(["%s"] * len(scores))
        try:
            rows = list(await fetch_all(
                _VECTOR_SQL_TEMPLATE.format(placeholders=placeholders),
                tuple(scores),
            ) or [])
        except Exception as exc:  # noqa: BLE001
            logger.warning("vector rows fetch failed: %s", exc)
        for row in rows:
            hit = scores.get(int(row.get("id") or 0))
            if hit is not None:
                row["rel"] = max(float(row.get("rel") or 0.0), hit)

    if episode_scores:
        # 原话也进向量：只靠 ngram 时「猫生病那晚」对不上「团子吐了一夜」。
        placeholders = ", ".join(["%s"] * len(episode_scores))
        try:
            raw_ids = [key - offset for key in episode_scores]
            episodes = await fetch_all(
                _EPISODE_VECTOR_SQL.format(placeholders=placeholders),
                (EPISODE_TAU_DAYS, *raw_ids),
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("episode vector rows failed: %s", exc)
            episodes = []
        for row in episodes or []:
            hit = episode_scores.get(offset + int(row.get("id") or 0))
            if hit is None:
                continue
            row["id"] = "ep-" + str(row["id"])
            row["rel"] = max(float(row.get("rel") or 0.0), hit)
            row["_lane"] = "vector"
            rows.append(row)
    return rows


# MySQL 的 ngram 分词没有停用词：短句里「今天」这种高频二元组会把不相干的行
# 拉进来（实测「今天吃了火锅」召回「今天加班到很晚」）。生产库靠 IDF 压住大部分，
# 但只含常见词的问句仍会漏进来，所以在 Python 侧再要求命中一个"实词二元组"。
_STOP_BIGRAMS = frozenset(
    """今天 明天 昨天 现在 刚才 时候 什么 怎么 真的 有点 还是 就是 可以 因为 所以
    但是 然后 已经 一直 我们 你们 他们 自己 一个 一下 觉得 知道 感觉""".split()
)


def _content_bigrams(text: str) -> set[str]:
    chars = [ch for ch in (text or "") if not ch.isspace()]
    grams = {chars[i] + chars[i + 1] for i in range(len(chars) - 1)}
    return {g for g in grams if g not in _STOP_BIGRAMS}


def _mentions_any(summary: str, grams: set[str]) -> bool:
    text = summary or ""
    return any(gram in text for gram in grams)


async def load_candidates(
    character_id: int,
    user_id: int,
    query: str | None,
    *,
    limit: int = CANDIDATE_LIMIT,
) -> list[dict[str, Any]]:
    text = (query or "").strip()
    vector_budget = float(getattr(get_settings(), "memory_vector_timeout_seconds", 0.35))

    async def _text_lane(sql: str, params: tuple, label: str) -> list[dict[str, Any]]:
        try:
            return list(await fetch_all(sql, params) or [])
        except Exception as exc:  # noqa: BLE001 - 老库没 V58/V59 时降级，但要留下线索
            logger.warning("memory %s lane failed: %s", label, exc)
            return []

    async def _vector_lane() -> list[dict[str, Any]]:
        # 向量路单独限时：它要走一次外网 embedding，线上实测 1–1.5 秒。等它会把整条
        # 召回拖过预算、连带 SQL 路一起作废；超时就只丢向量，文本路照常出块。
        try:
            return await asyncio.wait_for(
                _vector_rows(character_id, user_id, query, top_k=limit),
                timeout=vector_budget,
            )
        except asyncio.TimeoutError:
            logger.warning("vector recall timed out after %.2fs; text lanes only", vector_budget)
        except Exception as exc:  # noqa: BLE001 - 向量是加分项
            logger.warning("vector recall skipped: %s", exc)
        return []

    # 三条路并发：串行时 SQL 两跳 + 向量预算会叠成 600ms+（生产实测 544ms），
    # 而它们互不依赖。候选路失败仍抛给上层（data_readers 会退回旧路径），
    # 另两条失败只降级。
    candidate_result, episode_rows, vector_rows = await asyncio.gather(
        fetch_all(
            _CANDIDATE_SQL_RELEVANT if text else _CANDIDATE_SQL,
            (text, character_id, user_id, limit),
        ),
        _text_lane(
            _EPISODE_SQL_RELEVANT if text else _EPISODE_SQL,
            (EPISODE_TAU_DAYS, text, text, character_id, user_id, max(1, limit // 2)),
            "episode",
        ),
        _vector_lane(),
        return_exceptions=True,
    )
    if isinstance(candidate_result, BaseException):
        raise candidate_result
    rows = list(candidate_result or [])
    # 每个候选都带 lane：ngram 的 MATCH 分数与向量的 cosine 不可比，
    # 必须先按各自池子的最大值归一，再进同一个打分器（否则余弦 0.8 会被
    # 当成 0.8/20 的 4 分，向量路等于没开）。
    content = _content_bigrams(text)
    if content:
        rows = [row for row in rows
                if _mentions_any(str(row.get("summary") or ""), content)]
    rows = [{**row, "_lane": "ngram"} for row in rows]
    # core / pinned 是"设定"，单独占 core 槽位，不参与 recall 的名次竞争：
    # 混在一起时它们每轮都排前 5，把真正相关的记忆挤出去（演练里 5 个位置被
    # 501/517/533/527/504 占满，等于没召回）。
    merged = list(rows)
    seen = {row.get("id") for row in merged}

    # 现场（episode）：事实能推断，原话不能。它只占候选的一半上限，
    # 避免几条生动的原话把"设定"挤出去。
    if isinstance(episode_rows, BaseException):
        episode_rows = []
    episodes = [{**row, "_lane": "episode"} for row in episode_rows if row.get("id") not in seen]
    seen.update(row.get("id") for row in episodes)

    if isinstance(vector_rows, BaseException):
        vector_rows = []
    vector_rows = [{**row, "_lane": "vector"} for row in vector_rows]
    # 同一 id 可能同时被 ngram 与向量召回：都留着，交给 rank_rows 合并取较高的 rel，
    # 而不是"先到先得"——否则原话先被 ngram 以低分收录，就把向量的高分版本丢了。
    return [*merged, *episodes, *vector_rows]


async def mark_injected(rows: list[dict[str, Any]]) -> int:
    """记下"这一轮真的把哪几条给了模型"。

    冷却（3 天不再重复注入）与"多久没被想起"都依赖这个时间戳；没有它，
    `in_cooldown` 永远为假，同一句设定会每轮重复出现。episode 的 id 形如
    `ep-9`，要回到 `memory_episode` 记（V59 才有的列），否则同一段原话会
    每轮都冒出来。
    """
    meta_ids: list[int] = []
    episode_ids: list[int] = []
    archived_ids: list[int] = []
    for row in rows or []:
        raw = str(row.get("id") or "")
        if raw.startswith("ep-"):
            try:
                episode_ids.append(int(raw[3:]))
            except ValueError:
                continue
            continue
        try:
            memory_id = int(raw)
        except ValueError:
            continue
        meta_ids.append(memory_id)
        if str(row.get("fade_level") or "") == "archived":
            # 被召回到 = 她想起来了：抬回 faded 以上，否则每次都要重新"挖"一遍
            archived_ids.append(memory_id)
    if not meta_ids and not episode_ids:
        return 0

    touched = 0
    async with write.transaction() as cur:
        if meta_ids:
            placeholders = ", ".join(["%s"] * len(meta_ids))
            touched += await write.execute_write(
                cur,
                "UPDATE memory_meta SET last_injected_at = NOW(), last_recalled_at = NOW(), "
                "recall_count = recall_count + 1, "
                "strength = LEAST(1.000, strength + 0.05), last_decay_at = NOW() "
                f"WHERE id IN ({placeholders})",
                tuple(meta_ids),
            )
        if archived_ids:
            placeholders = ", ".join(["%s"] * len(archived_ids))
            touched += await write.execute_write(
                cur,
                "UPDATE memory_meta SET strength = GREATEST(strength, 0.55), "
                "fade_level = 'active', status = 'active', archived_at = NULL, "
                "last_decay_at = NOW(), "
                f"revival_count = revival_count + 1 WHERE id IN ({placeholders})",
                tuple(archived_ids),
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


# 单个候选只有 1e-8 级别的 MATCH 分数时（同一路只有一条、IDF 为 0），
# 按 lane 归一会被放大成 1.0，和"真的命中"平起平坐。低于此值就当没命中。
# 这一路的最强分到什么程度才算"可信"：ngram 的 0.1、cosine 的 0.1 都可视为强信号。
LANE_STRONG_REL = 0.1

MIN_LANE_REL = 1e-4


def _raw_rel(row: dict[str, Any]) -> float:
    raw = float(row.get("rel") or 0.0)
    return raw if raw >= MIN_LANE_REL else 0.0


def rank_rows(
    rows: list[dict[str, Any]],
    *,
    limit: int = retrieve.DEFAULT_RECALL_LIMIT,
    now: datetime | None = None,
) -> list[dict[str, Any]]:
    """跨路融合 + 打分 + 冷却 + MMR。

    为什么要融合而不是把每一路各自归一：ngram 的 MATCH 分数与向量 cosine 不在
    一个量纲上，各自除以本路最大值会让"这一路最强的那个"都变成 1.0 —— 40 条
    事实里总有一条 MATCH 最高，于是事实永远挤满前五，向量召回到的原话反而排不进来。
    这里按名次做 RRF（k=60），只在同一路内部比高低，跨路比的是"被几路同时认可"。
    """
    if not rows:
        return []

    merged: dict[str, dict[str, Any]] = {}
    for row in rows:
        key = str(row.get("id"))
        lane = str(row.get("_lane") or "ngram")
        rel = _raw_rel(row)
        current = merged.get(key)
        if current is None:
            merged[key] = {**row, "_lanes": {lane: rel}}
            continue
        current["_lanes"][lane] = max(rel, current["_lanes"].get(lane, 0.0))
        if rel > _raw_rel(current):
            current["rel"] = rel

    lanes: dict[str, list[dict[str, Any]]] = {}
    for row in merged.values():
        for lane in row["_lanes"]:
            lanes.setdefault(lane, []).append(row)
    for ranked in lanes.values():
        ranked.sort(key=lambda item: item["_lanes"].get(str(item.get("_lane") or ""), 0.0)
                    or max(item["_lanes"].values()), reverse=True)
        for position, row in enumerate(ranked, start=1):
            row["_rrf"] = row.get("_rrf", 0.0) + 1.0 / (retrieve.RRF_K + position)

    best = max((row.get("_rrf", 0.0) for row in merged.values()), default=0.0)
    if best <= 0:
        return []

    scored: list[dict[str, Any]] = []
    for row in merged.values():
        core = str(row.get("layer") or "") == "core" or bool(row.get("pinned"))
        # 设定与钉住项不受冷却约束：它们是"她是谁"，不该因为刚提过就消失。
        if not core and retrieve.in_cooldown(row, now):
            continue
        if not core and max(row["_lanes"].values(), default=0.0) <= 0:
            continue
        relevance = 1.0 if core else row.get("_rrf", 0.0) / best
        scored.append({
            **row,
            "score": retrieve.score_candidate(row, relevance=relevance, now=now),
        })

    scored.sort(key=lambda row: row["score"], reverse=True)
    return retrieve.mmr_select(scored, limit=limit)


async def core_rows(character_id: int, user_id: int, *, limit: int = 6) -> list[dict[str, Any]]:
    """设定类记忆（core / pinned）；单独渲染，不参与 recall 排序。"""
    rows = list(await fetch_all(_CORE_SQL, (character_id, user_id)) or [])
    if len(rows) > limit:
        logger.warning("core lane truncated: %d rows, only %d fit the slot", len(rows), limit)
    return [{**row, "rel": 1.0, "_lane": "core"} for row in rows[:limit]]


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


__all__ = ["core_rows", "CANDIDATE_LIMIT", "load_candidates", "rank_rows", "recall", "render_rows",
           "render_rows_limited"]

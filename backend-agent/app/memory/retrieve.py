"""召回打分：把候选行变成"这一轮该说的几句话"。

分离成纯函数的原因：召回是记忆系统里最容易被"调参调坏"的一段。
它必须能在没有 MySQL、没有 Milvus、没有模型的情况下被单测覆盖，
否则每次改权重都只能靠线上体感。

四路信号（与文档 §6 一致）：
    score = 0.50 * relevance + 0.25 * s_eff + 0.15 * importance + 0.10 * recency

`s_eff` 是惰性求值的记忆强度（§7.6.3）：strength 存库，读取时按两条指数
衰减算出来。两条指数而不是一条：真实遗忘曲线是"先快后慢"，
单指数要么开头忘得不够快，要么尾巴掉得太快。
"""

from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Any, Iterable, Sequence

# 融合与去重的默认参数（文档 §6.4 / §6.5）
RRF_K = 60
MMR_LAMBDA = 0.7
COOLDOWN_DAYS = 3
DEFAULT_RECALL_LIMIT = 5


def _as_datetime(value: Any) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    try:
        parsed = datetime.fromisoformat(str(value))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _now(now: datetime | None) -> datetime:
    return now or datetime.now(timezone.utc)


def _days_between(later: datetime, earlier: datetime) -> float:
    return max(0.0, (later - earlier).total_seconds() / 86400.0)


def effective_strength(row: dict[str, Any], now: datetime | None = None) -> float:
    """记忆强度 s_eff ∈ [0,1]。`tau_days<=0` 表示永不衰减（core / pinned）。"""
    tau = float(row.get("tau_days") or 0)
    if tau <= 0:
        return 1.0

    strength = float(row.get("strength") if row.get("strength") is not None else 1.0)
    strength = max(0.0, min(1.0, strength))
    anchor = _as_datetime(row.get("last_decay_at")) or _as_datetime(row.get("valid_from")) \
        or _as_datetime(row.get("created_at"))
    if anchor is None:
        return strength

    delta = _days_between(_now(now), anchor)
    fast = math.exp(-delta / (tau / 6.0))
    slow = math.exp(-delta / (2.5 * tau))
    return max(0.0, min(1.0, strength * (0.7 * fast + 0.3 * slow)))


def recency_weight(row: dict[str, Any], now: datetime | None = None) -> float:
    """越近越容易被想起；用 0.995/小时的半衰节奏（Generative Agents 同款）。"""
    anchor = _as_datetime(row.get("event_time")) or _as_datetime(row.get("created_at"))
    if anchor is None:
        return 0.5
    hours = max(0.0, (_now(now) - anchor).total_seconds() / 3600.0)
    return 0.995 ** hours


def score_candidate(
    row: dict[str, Any],
    *,
    relevance: float = 0.0,
    now: datetime | None = None,
) -> float:
    return (
        0.50 * max(0.0, min(1.0, relevance))
        + 0.25 * effective_strength(row, now)
        + 0.15 * max(0.0, min(1.0, float(row.get("importance") or 0.0)))
        + 0.10 * recency_weight(row, now)
    )


def in_cooldown(row: dict[str, Any], now: datetime | None = None, days: int = COOLDOWN_DAYS) -> bool:
    """刚说过的不要再提（同一条 3 天内重复注入会像祥林嫂）。"""
    last = _as_datetime(row.get("last_injected_at"))
    if last is None:
        return False
    return _days_between(_now(now), last) < days


def rrf_fuse(
    lists: Sequence[Sequence[dict[str, Any]]],
    *,
    k: int = RRF_K,
    key: str = "id",
) -> list[dict[str, Any]]:
    """Reciprocal Rank Fusion：只信名次，不信各路分数量纲。

    融合后每行带 `rrf` 字段；同 id 的行以先出现的那份为准（后到的只加分）。
    """
    scores: dict[str, float] = {}
    first_seen: dict[str, dict[str, Any]] = {}
    for ranked in lists:
        for position, row in enumerate(ranked):
            row_id = str(row.get(key))
            if not row_id or row_id == "None":
                continue
            scores[row_id] = scores.get(row_id, 0.0) + 1.0 / (k + position + 1)
            first_seen.setdefault(row_id, row)

    fused = []
    for row_id, weight in sorted(scores.items(), key=lambda item: item[1], reverse=True):
        row = dict(first_seen[row_id])
        row["rrf"] = weight
        fused.append(row)
    return fused


def text_overlap(a: str, b: str) -> float:
    """字符二元组包含度：中文短句上比 Jaccard 稳（Jaccard 会被长度稀释）。"""
    grams_a = {a[i:i + 2] for i in range(len(a) - 1)} if len(a) > 1 else {a}
    grams_b = {b[i:i + 2] for i in range(len(b) - 1)} if len(b) > 1 else {b}
    grams_a.discard("")
    grams_b.discard("")
    if not grams_a or not grams_b:
        return 0.0
    return len(grams_a & grams_b) / len(grams_a)


def mmr_select(
    rows: Sequence[dict[str, Any]],
    *,
    limit: int = DEFAULT_RECALL_LIMIT,
    lambda_: float = MMR_LAMBDA,
) -> list[dict[str, Any]]:
    """最大边际相关：既要贴题，也别五条都在说同一件事。"""
    if limit <= 0:
        return []

    pool = list(rows)
    picked: list[dict[str, Any]] = []
    while pool and len(picked) < limit:
        best_index, best_value = 0, float("-inf")
        for index, row in enumerate(pool):
            relevance = float(row.get("score") or row.get("rrf") or 0.0)
            redundancy = max(
                (text_overlap(str(row.get("summary") or ""), str(chosen.get("summary") or ""))
                 for chosen in picked),
                default=0.0,
            )
            value = lambda_ * relevance - (1.0 - lambda_) * redundancy
            if value > best_value:
                best_index, best_value = index, value
        picked.append(pool.pop(best_index))
    return picked


def _stamp(row: dict[str, Any]) -> str:
    anchor = _as_datetime(row.get("event_time")) or _as_datetime(row.get("created_at"))
    return anchor.strftime("%m-%d") if anchor else ""


def confidence_mark(row: dict[str, Any], now: datetime | None = None) -> str:
    """确定 / 模糊：让模型知道哪句可以说死，哪句只能说"好像"。"""
    level = str(row.get("fade_level") or "active")
    return "确定" if level in ("active", "core") and effective_strength(row, now) >= 0.6 else "模糊"


def render_recall_block(rows: Iterable[dict[str, Any]], now: datetime | None = None) -> str:
    """渲染成注入用的几行字；空输入返回空串。"""
    lines = []
    for row in rows:
        text = str(row.get("quote") or row.get("summary") or "").strip()
        if not text:
            continue
        stamp = _stamp(row)
        mark = confidence_mark(row, now)
        prefix = f"[{stamp}·{mark}]" if stamp else f"[{mark}]"
        lines.append(f"- {prefix} {text}")
    return "\n".join(lines)


__all__ = [
    "COOLDOWN_DAYS",
    "confidence_mark",
    "effective_strength",
    "in_cooldown",
    "mmr_select",
    "recency_weight",
    "render_recall_block",
    "rrf_fuse",
    "score_candidate",
    "text_overlap",
]

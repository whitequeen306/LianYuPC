"""记忆子系统的运行态体检：开开关之前先看这里，而不是猜。

只读、只统计，不写库；任何一条查询失败都记为 None，不让体检本身变成故障点。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from app.config import get_settings
from app.db import fetch_all

logger = logging.getLogger(__name__)

# key -> (SQL, 说明)。SQL 必须只读且单行聚合，避免体检拖慢主库。
QUERIES: dict[str, str] = {
    "memories_active": (
        "SELECT COUNT(*) AS n FROM memory_meta WHERE status = 'active' AND muted = 0"
    ),
    "memories_faded": (
        "SELECT COUNT(*) AS n FROM memory_meta WHERE fade_level IN ('faded', 'archived', 'dormant')"
    ),
    "memories_superseded": (
        "SELECT COUNT(*) AS n FROM memory_meta WHERE status = 'superseded'"
    ),
    "episodes_active": (
        "SELECT COUNT(*) AS n FROM memory_episode WHERE status = 'active'"
    ),
    "outbox_pending": (
        "SELECT COUNT(*) AS n FROM memory_outbox WHERE status IN ('PENDING', 'RUNNING')"
    ),
    "outbox_dead": "SELECT COUNT(*) AS n FROM memory_outbox WHERE status = 'DEAD'",
    "last_decay_at": "SELECT MAX(last_decay_at) AS at FROM memory_meta",
    "last_episode_at": "SELECT MAX(created_at) AS at FROM memory_episode",
    "last_reflection_at": (
        "SELECT MAX(created_at) AS at FROM memory_meta WHERE layer = 'reflection'"
    ),
}


def flags() -> dict[str, bool]:
    """当前开关快照——排查"为什么没生效"第一步就看它。"""
    settings = get_settings()
    return {
        "scene_card": bool(getattr(settings, "scene_card_enabled", False)),
        "scene_extract": bool(getattr(settings, "scene_extract_enabled", False)),
        "recall_v3": bool(getattr(settings, "memory_recall_v3_enabled", False)),
        "vector_recall": bool(getattr(settings, "memory_vector_recall_enabled", False)),
        "dedupe_similarity": bool(getattr(settings, "memory_dedupe_similarity_enabled", False)),
        "dedupe_llm_review": bool(getattr(settings, "memory_dedupe_llm_review_enabled", False)),
        "shadow": bool(getattr(settings, "memory_shadow_enabled", False)),
        "consolidate": bool(getattr(settings, "memory_consolidate_enabled", False)),
        "history_trim": bool(getattr(settings, "history_trim_enabled", False)),
    }


def _count(result: list[dict[str, Any]] | None) -> int | None:
    if not result:
        return None
    value = result[0].get("n")
    return int(value) if value is not None else None


def _timestamp(result: list[dict[str, Any]] | None) -> str | None:
    if not result:
        return None
    value = result[0].get("at")
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value) if value else None


def build_health(
    flag_map: dict[str, bool],
    results: dict[str, list[dict[str, Any]] | None],
    now: datetime | None = None,
) -> dict[str, Any]:
    """把开关与聚合结果拼成一份可读的体检报告（纯函数，便于测试）。"""
    counters = {key: _count(results.get(key)) for key in QUERIES if key.startswith(("memories_", "episodes_", "outbox_"))}
    stamps = {key: _timestamp(results.get(key)) for key in QUERIES if key.startswith("last_")}

    warnings: list[str] = []
    if counters.get("outbox_dead"):
        warnings.append("outbox_dead>0：有记忆没写进向量库，检索会漏")
    if counters.get("outbox_pending") and (counters["outbox_pending"] or 0) > 50:
        warnings.append("outbox_pending>50：向量回填积压")
    if flag_map.get("recall_v3") and not counters.get("episodes_active"):
        warnings.append("recall_v3 已开但没有 episode：共同经历一路会是空的")

    return {
        "now": (now or datetime.now()).isoformat(timespec="seconds"),
        "flags": flag_map,
        "counters": counters,
        "timestamps": stamps,
        "warnings": warnings,
    }


async def collect(now: datetime | None = None) -> dict[str, Any]:
    results: dict[str, list[dict[str, Any]] | None] = {}
    for key, sql in QUERIES.items():
        try:
            results[key] = await fetch_all(sql)
        except Exception as exc:  # noqa: BLE001 - 体检部分失败也要能返回其余部分
            logger.warning("memory health query failed: %s (%s)", key, exc)
            results[key] = None
    return build_health(flags(), results, now)


__all__ = ["QUERIES", "build_health", "collect", "flags"]

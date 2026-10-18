"""数据块读取（只读 SQL）—— 让 Python 不再依赖 Java 传入 prompt 数据。

每读一块只做「查表 → 返回原始字段」，格式化仍由各自的 format 模块负责
（`relationship_context` / `emotion_block` / `session_summary_block`）。
这样「读错」和「格式化错」可以分开定位。

对齐 Java 的查询条件（全部只读、参数化）：
- `relationship_state`：按 (user_id, character_id) 取一行
- `relationship_event`：按 (user_id, character_id) 取最近 N 条非空 summary，created_at 倒序
- `character_state`：按 (character_id, user_id) 取一行

无数据时返回 None —— 与 Java「查不到就不追加该块」的语义一致。
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings
from app.db import fetch_all

logger = logging.getLogger(__name__)


async def load_relationship(user_id: int, character_id: int) -> dict[str, Any] | None:
    """返回 {phase, trust, intimacy, security, anticipation, events}；无状态行返回 None。"""
    if not user_id or not character_id:
        return None
    rows = await fetch_all(
        "SELECT phase, trust_score, intimacy_score, security_score, anticipation_score "
        "FROM relationship_state WHERE user_id = %s AND character_id = %s LIMIT 1",
        (user_id, character_id),
    )
    if not rows:
        return None
    row = rows[0]
    settings = get_settings()
    events = await fetch_all(
        "SELECT summary FROM relationship_event "
        "WHERE user_id = %s AND character_id = %s AND summary IS NOT NULL "
        "ORDER BY created_at DESC LIMIT %s",
        (user_id, character_id, max(1, settings.relationship_recent_events)),
    )
    return {
        "phase": row.get("phase"),
        "trust": int(row.get("trust_score") or 0),
        "intimacy": int(row.get("intimacy_score") or 0),
        "security": int(row.get("security_score") or 0),
        "anticipation": int(row.get("anticipation_score") or 0),
        "events": [str(e["summary"]) for e in events if e.get("summary")],
    }


async def load_character_owner(character_id: int) -> int | None:
    """角色的所有者 id。

    情绪状态在 Java 侧是按 **(character_id, owner_user_id)** 存的，不是按会话用户。
    用请求者 id 去读会读到另一行（或读不到），在影子比对里表现为「情绪块内容不同」。
    """
    if not character_id:
        return None
    rows = await fetch_all(
        "SELECT owner_user_id FROM `character` WHERE id = %s LIMIT 1", (character_id,))
    if not rows:
        return None
    owner = rows[0].get("owner_user_id")
    return int(owner) if owner is not None else None


async def load_emotion(character_id: int, user_id: int) -> dict[str, Any] | None:
    """返回情绪状态原始字段；无行返回 None。"""
    if not character_id or not user_id:
        return None
    rows = await fetch_all(
        "SELECT current_emotion, emotion_intensity, status_text, previous_emotion "
        "FROM character_state WHERE character_id = %s AND user_id = %s LIMIT 1",
        (character_id, user_id),
    )
    if not rows:
        return None
    row = rows[0]
    return {
        "current_emotion": row.get("current_emotion"),
        "emotion_intensity": row.get("emotion_intensity"),
        "status_text": row.get("status_text"),
        "previous_emotion": row.get("previous_emotion"),
    }


async def load_memory_context(user_id: int, character_id: int,
                            conversation_id: int | None = None,
                            include_memory: bool = True,
                            include_relationship: bool = True,
                            include_session: bool = True) -> str | None:
    """记忆 + 关系 + 会话摘要的连接结果（对齐 Java `joinBlocks`）。

    复用 `profile_context` 的格式化；关系块与会话摘要块分别由各自模块产出。
    """
    from app.memory.profile_context import build_profile_context
    from app.relationship_context import build_relationship_context
    from app.session_summary_block import format_session_summary

    parts: list[str] = []

    if not include_memory:
        metas = []
    else:
        metas = await fetch_all(
            "SELECT summary, memory_type, importance FROM memory_meta "
            "WHERE character_id = %s AND user_id = %s AND importance >= %s "
            "ORDER BY importance DESC, created_at DESC LIMIT %s",
            (character_id, user_id, get_settings().memory_importance_threshold,
             get_settings().memory_search_candidate_count),
        )
    memory_block = build_profile_context([(m["summary"], m["memory_type"]) for m in metas])
    if memory_block:
        parts.append(memory_block)

    relationship = await load_relationship(user_id, character_id) if include_relationship else None
    if relationship:
        parts.append(build_relationship_context(
            phase=relationship["phase"], trust=relationship["trust"],
            intimacy=relationship["intimacy"], security=relationship["security"],
            anticipation=relationship["anticipation"], recent_events=relationship["events"],
        ))

    summary = format_session_summary(
        await load_session_summary(conversation_id) if include_session else None)
    if summary:
        parts.append(summary)

    return "\n\n".join(parts) if parts else None


SESSION_SUMMARY_KEY_PREFIX = "session:summary:"


async def load_session_summary(conversation_id: int | None) -> str | None:
    """会话摘要文本。

    存储位置取自 Java：Redis key `session:summary:{conversationId}`，
    value 是 `SessionSummaryState` 的 JSON（含 text 字段）。
    只用只读的 cache_get_json，不触碰 Java 的写入路径。
    """
    if not conversation_id:
        return None
    from app.db import cache_get_json

    payload = await cache_get_json(SESSION_SUMMARY_KEY_PREFIX + str(conversation_id))
    if isinstance(payload, str):
        return payload
    if isinstance(payload, dict):
        text = payload.get("text")
        return str(text) if text else None
    return None


__all__ = ["load_character_owner", "load_emotion", "load_memory_context",
           "load_relationship", "load_session_summary"]

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


async def _load_recent_rows(character_id: int, user_id: int) -> list[dict[str, Any]]:
    """基础候选：与 Java `loadRecentMemoryMetas` 的 SQL 分支一致。"""
    return await fetch_all(
        "SELECT id, summary, memory_type, importance FROM memory_meta "
        "WHERE character_id = %s AND user_id = %s AND importance >= %s "
        "ORDER BY importance DESC, created_at DESC LIMIT %s",
        (character_id, user_id, get_settings().memory_importance_threshold,
         get_settings().memory_search_candidate_count),
    )


PROFILE_PREFIX = "【长期记忆/"


def _extract_profile_slot(summary: str | None) -> str | None:
    """对齐 Java `extractProfileSlot`：取 `【长期记忆/<slot>】` 中的 slot。"""
    if not summary or not summary.startswith(PROFILE_PREFIX):
        return None
    end = summary.find("】")
    if end <= len(PROFILE_PREFIX):
        return None
    return summary[len(PROFILE_PREFIX):end]


async def _load_profile_facts(character_id: int, user_id: int) -> list[dict[str, Any]]:
    """长期画像事实：对齐 Java `loadProfileFactMetas`。

    Java 的语义是「**每个 slot 保留最新一条**」（按 created_at 倒序取 80 条后去重），
    不是简单的 importance 排序 —— 这个细节会直接改变画像块内容。
    """
    rows = await fetch_all(
        "SELECT id, summary, memory_type, importance FROM memory_meta "
        "WHERE character_id = %s AND user_id = %s AND summary LIKE %s "
        "ORDER BY created_at DESC LIMIT 80",
        (character_id, user_id, PROFILE_PREFIX + "%"),
    )
    latest_by_slot: dict[str, dict[str, Any]] = {}
    for row in rows:
        slot = _extract_profile_slot(row.get("summary"))
        if slot is not None and slot not in latest_by_slot:
            latest_by_slot[slot] = row
    return list(latest_by_slot.values())


def _pick_like_keyword(text: str | None) -> str | None:
    """对齐 Java `pickLikeKeyword`：短于 4 字不 boost；取首个 2-12 字 token。"""
    if not text or len(text) < 4:
        return None
    for token in text.split():
        trimmed = token.strip()
        if 2 <= len(trimmed) <= 12:
            return trimmed
    return text[:12] if len(text) > 12 else text


async def _load_like_boosted(character_id: int, user_id: int,
                             last_user_message: str | None) -> list[dict[str, Any]]:
    keyword = _pick_like_keyword(last_user_message)
    if keyword is None:
        return []
    return await fetch_all(
        "SELECT id, summary, memory_type, importance FROM memory_meta "
        "WHERE character_id = %s AND user_id = %s AND importance >= %s AND summary LIKE %s "
        "ORDER BY importance DESC, created_at DESC LIMIT %s",
        (character_id, user_id, get_settings().memory_importance_threshold,
         f"%{keyword}%", 5),
    )


TYPE_LABELS = (
    ("FACT", "用户画像"),
    ("EMOTION", "近期情绪线索"),
    ("RELATION", "关系事件"),
    ("RITUAL", "专属仪式"),
)


def _render_profile_block(rows: list[dict[str, Any]]) -> str | None:
    """按类型分组渲染，保持顺序并去重 —— 对齐 Java `joinByType`。"""
    parts: list[str] = []
    for memory_type, label in TYPE_LABELS:
        seen: set[str] = set()
        lines: list[str] = []
        for row in rows:
            if str(row.get("memory_type") or "").upper() != memory_type:
                continue
            summary = str(row.get("summary") or "").strip()
            if not summary or summary in seen:
                continue
            seen.add(summary)
            lines.append("- " + summary)
        if lines:
            parts.append("[" + label + "]\n" + "\n".join(lines))
    return "\n\n".join(parts) if parts else None


async def load_profile_block(character_id: int, user_id: int,
                             last_user_message: str | None = None) -> str | None:
    """记忆画像块：镜像 Java `retrieveProfileContext` 的取数与合并顺序。

    顺序很重要：**长期画像事实优先**，再追加基础候选里未出现过的条目，
    最后补充按关键词 LIKE 命中的条目（同样去重）。
    Java 侧 `joinByType` 用 `distinct()` 保序去重，所以合并顺序会改变最终内容。
    """
    facts = await _load_profile_facts(character_id, user_id)
    base = await _load_recent_rows(character_id, user_id)
    boosted = await _load_like_boosted(character_id, user_id, last_user_message)

    merged: list[dict[str, Any]] = []
    seen_ids: set[int] = set()
    for row in [*facts, *base, *boosted]:
        row_id = row.get("id")
        if row_id is not None:
            if int(row_id) in seen_ids:
                continue
            seen_ids.add(int(row_id))
        merged.append(row)
    return _render_profile_block(merged)


async def load_memory_context(user_id: int, character_id: int,
                            conversation_id: int | None = None,
                            include_memory: bool = True,
                            include_relationship: bool = True,
                            include_session: bool = True,
                            last_user_message: str | None = None,
                            session_summary_override: str | None = None) -> str | None:
    """记忆 + 关系 + 会话摘要的连接结果（对齐 Java `joinBlocks`）。

    记忆块复用与 Java 相同的取数策略（见 `load_profile_block`）。
    """
    from app.relationship_context import build_relationship_context
    from app.session_summary_block import format_session_summary

    parts: list[str] = []

    memory_block = (await load_profile_block(character_id, user_id, last_user_message)
                    if include_memory else None)
    if memory_block:
        parts.append(memory_block)

    relationship = await load_relationship(user_id, character_id) if include_relationship else None
    if relationship:
        parts.append(build_relationship_context(
            phase=relationship["phase"], trust=relationship["trust"],
            intimacy=relationship["intimacy"], security=relationship["security"],
            anticipation=relationship["anticipation"], recent_events=relationship["events"],
        ))

    # 显式传入优先：摘要在本轮可能被更新，异步读会拿到新值造成假差异
    summary = format_session_summary(
        session_summary_override if session_summary_override is not None
        else (await load_session_summary(conversation_id) if include_session else None))
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

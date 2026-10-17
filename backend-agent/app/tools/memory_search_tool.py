"""`memory_search` —— 长期记忆检索（MySQL + Redis 版本）。

**与 Java 的差异（必须知道）**：Java 是 `Redis 向量缓存 → Milvus 向量检索 → Rerank → MySQL 兜底`；
本实现走的是 Java **向量不可用时的降级分支**——语义缓存 →（关键词 LIKE 提升 + 重要性排序）→ topK。
排序质量低于向量+rerank，但语义与 Java 的 fallback 完全一致，且不引入 Milvus/embedding 依赖（总纲 D7）。
Milvus 接入留待 P5；届时只需替换 `search_semantic()` 内部实现，工具契约不变。
"""

from __future__ import annotations

import hashlib
import logging
import re
from typing import Any

from app.config import get_settings
from app.db import cache_get_json, cache_put_json, fetch_all
from app.tools.registry import ToolSpec, register

logger = logging.getLogger(__name__)

SEMANTIC_KEY_PREFIX = "memory:semantic:"
RECENT_KEY_PREFIX = "memory:recent:"

_WHITESPACE = re.compile(r"\s+")


def hash_query(query: str) -> str:
    """对齐 Java `MemoryCacheService.hashQuery`：trim + 压缩空白 + 小写 + SHA-256 hex。"""
    normalized = _WHITESPACE.sub(" ", (query or "").strip()).lower()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def pick_like_keyword(text: str) -> str | None:
    """对齐 Java `MemoryRetriever.pickLikeKeyword`：首个 2–12 字符 token，否则取前 12 字符。"""
    if text is None or len(text) < 4:
        return None
    for token in text.split():
        trimmed = token.strip()
        if 2 <= len(trimmed) <= 12:
            return trimmed
    return text[:12] if len(text) > 12 else text


async def _fetch_by_importance(user_id: int, character_id: int, limit: int) -> list[dict[str, Any]]:
    settings = get_settings()
    return await fetch_all(
        "SELECT id, summary, memory_type, importance, created_at FROM memory_meta "
        "WHERE character_id = %s AND user_id = %s AND importance >= %s "
        "ORDER BY importance DESC, created_at DESC LIMIT %s",
        (character_id, user_id, settings.memory_importance_threshold, limit),
    )


async def _fetch_like_boosted(user_id: int, character_id: int, keyword: str, limit: int) -> list[dict[str, Any]]:
    settings = get_settings()
    return await fetch_all(
        "SELECT id, summary, memory_type, importance, created_at FROM memory_meta "
        "WHERE character_id = %s AND user_id = %s AND importance >= %s AND summary LIKE %s "
        "ORDER BY importance DESC, created_at DESC LIMIT %s",
        (character_id, user_id, settings.memory_importance_threshold, f"%{keyword}%", limit),
    )


def _merge(base: list[dict[str, Any]], boosted: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """对齐 Java `loadContextMemoryMetas` 的合并顺序：base 优先，boosted 去重追加。"""
    merged: dict[Any, dict[str, Any]] = {}
    for row in base:
        merged[row.get("id")] = row
    for row in boosted:
        merged.setdefault(row.get("id"), row)
    return list(merged.values())


async def search_semantic(
    user_id: int,
    character_id: int,
    query: str,
    top_k: int | None = None,
) -> list[str]:
    """返回 `- 摘要` 列表（对齐 Java `searchSemantic` 的输出形状）。"""
    settings = get_settings()
    limit = top_k or settings.memory_tool_top_k
    if not query or not query.strip():
        return []

    key = f"{SEMANTIC_KEY_PREFIX}{user_id}:{character_id}"
    field = hash_query(query)
    cached = await cache_get_json(key, field)
    if isinstance(cached, list):
        return cached[:limit]

    try:
        base = await _fetch_by_importance(
            user_id, character_id, settings.memory_search_candidate_count
        )
        keyword = pick_like_keyword(query.strip())
        boosted: list[dict[str, Any]] = []
        if keyword:
            try:
                boosted = await _fetch_like_boosted(
                    user_id, character_id, keyword, settings.memory_like_boost_limit
                )
            except Exception as exc:  # noqa: BLE001 - LIKE 提升失败不影响主检索
                logger.debug("memory like-boost failed: %s", exc)
        rows = _merge(base, boosted)
    except Exception as exc:  # noqa: BLE001 - 数据层失败降级为空，不炸对话
        logger.warning("memory search db failed: %s", exc)
        return []

    summaries = [
        f"- {row['summary']}" for row in rows
        if row.get("summary") and str(row["summary"]).strip()
    ][:limit]
    if not summaries:
        return []
    await cache_put_json(key, summaries, settings.memory_semantic_ttl_minutes * 60, field=field)
    return summaries


async def handle_memory_search(arguments: dict[str, Any]) -> str:
    query = str((arguments or {}).get("query") or "").strip()
    user_id = (arguments or {}).get("_user_id")
    character_id = (arguments or {}).get("_character_id")
    if not query:
        return "（缺少 query）"
    if not user_id or not character_id:
        return "（当前没有可检索的角色上下文）"

    lines = await search_semantic(int(user_id), int(character_id), query)
    if not lines:
        logger.debug("memory_search empty: userId=%s, characterId=%s", user_id, character_id)
        return "（未找到相关长期记忆）"
    logger.info("memory_search hit: userId=%s, characterId=%s, count=%d",
                user_id, character_id, len(lines))
    return "\n".join(lines)


MEMORY_SEARCH_TOOL = ToolSpec(
    name="memory_search",
    description=(
        "按语义检索与当前用户相关的长期对话记忆（向量库）。"
        "当用户提到「还记得吗」「上次说过」「以前」「之前聊过」或需要回忆具体往事时调用。"
        "寒暄、纯表情、无需回忆时不要调用。query 用简短中文关键词或问句。"
    ),
    parameters={
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "检索 query，例如：用户喜欢的食物、上次聊的工作烦恼",
            }
        },
        "required": ["query"],
    },
    handler=handle_memory_search,
)

register(MEMORY_SEARCH_TOOL)

__all__ = [
    "MEMORY_SEARCH_TOOL",
    "handle_memory_search",
    "hash_query",
    "pick_like_keyword",
    "search_semantic",
]

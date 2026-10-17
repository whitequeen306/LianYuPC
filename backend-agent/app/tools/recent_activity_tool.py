"""`get_my_recent_life` —— 角色近 7 日日记 / 朋友圈 / 评论摘要。

逐条移植 Java `CharacterRecentActivityService`：
- 窗口 7 天；日记/动态/评论各取 2 条；合并后按时间倒序取前 5 条
- 每条截 40 字符（压缩空白 + 省略号）
- 四语种标题与句式
- Redis 缓存 `activity:prompt:{userId}:{characterId}`，TTL 1 小时
"""

from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta
from typing import Any

from app.config import get_settings
from app.db import cache_get_json, cache_put_json, fetch_all
from app.language_gate import Language, from_code
from app.tools.registry import ToolSpec, register

logger = logging.getLogger(__name__)

ACTIVITY_KEY_PREFIX = "activity:prompt:"
_WHITESPACE = re.compile(r"\s+")


def _clip(text: str | None, chars: int) -> str:
    if not text:
        return ""
    trimmed = _WHITESPACE.sub(" ", text.strip())
    return trimmed if len(trimmed) <= chars else trimmed[:chars] + "…"


def _section_title(lang: str) -> str:
    table = {
        Language.EN: "=== Your recent life activities (background context, do not recap line by line) ===",
        Language.JA: "=== 最近の生活の出来事（背景記憶・逐条復述しない） ===",
        Language.ZH_TW: "=== 你最近的生活動態（背景記憶，勿逐條復述） ===",
    }
    return table.get(from_code(lang), "=== 你最近的生活动态（背景记忆，勿逐条复述） ===")


def _format_diary(lang: str, detail: str) -> str:
    return {
        Language.EN: f"Wrote diary {detail}",
        Language.JA: f"日記を書いた {detail}",
    }.get(from_code(lang), f"写了日记{detail}")


def _format_post(lang: str, content: str) -> str:
    return {
        Language.EN: f'Posted on moments "{content}"',
        Language.JA: f"モーメンツに投稿「{content}」",
    }.get(from_code(lang), f"发了朋友圈「{content}」")


def _format_comment(lang: str, peer_name: str, content: str) -> str:
    return {
        Language.EN: f"Commented on {peer_name}'s post: {content}",
        Language.JA: f"{peer_name} の投稿にコメント：{content}",
    }.get(from_code(lang), f"在 {peer_name} 的动态下评论了「{content}」")


def _format_date(lang: str, at: datetime | None) -> str:
    if at is None:
        return ""
    if from_code(lang) is Language.EN:
        return at.strftime("%b %d")
    return f"{at.month}月{at.day}日"


async def build_recent_activity(user_id: int, character_id: int, lang: str) -> str:
    """拼装近况块；无记录返回空串（对齐 Java `buildFormatted` 返回 ""）。"""
    settings = get_settings()
    since = datetime.now() - timedelta(days=7)
    lines: list[tuple[datetime | None, str]] = []

    diaries = await fetch_all(
        "SELECT title, content, created_at FROM character_diary "
        "WHERE user_id = %s AND character_id = %s AND created_at >= %s "
        "ORDER BY created_at DESC LIMIT 2",
        (user_id, character_id, since),
    )
    for diary in diaries:
        title = (diary.get("title") or "").strip()
        body = _clip(diary.get("content"), settings.recent_activity_snippet_chars)
        detail = body if not title else f"《{_clip(title, settings.recent_activity_snippet_chars)}》——{body}"
        lines.append((diary.get("created_at"), _format_diary(lang, detail)))

    posts = await fetch_all(
        "SELECT content, created_at FROM moments_post "
        "WHERE author_type = 'CHARACTER' AND character_id = %s AND user_id = %s AND created_at >= %s "
        "ORDER BY created_at DESC LIMIT 2",
        (character_id, user_id, since),
    )
    for post in posts:
        content = _clip(post.get("content"), settings.recent_activity_snippet_chars)
        lines.append((post.get("created_at"), _format_post(lang, content)))

    comments = await fetch_all(
        "SELECT post_id, content, created_at FROM moments_comment "
        "WHERE character_id = %s AND author_type = 'CHARACTER' AND created_at >= %s "
        "ORDER BY created_at DESC LIMIT 2",
        (character_id, since),
    )
    if comments:
        post_ids = [c["post_id"] for c in comments if c.get("post_id")]
        peer_names = await _resolve_peer_names(post_ids)
        for comment in comments:
            peer = peer_names.get(comment.get("post_id"), "某人")
            content = _clip(comment.get("content"), settings.recent_activity_snippet_chars)
            lines.append((comment.get("created_at"), _format_comment(lang, peer, content)))

    if not lines:
        return ""

    lines.sort(key=lambda item: item[0] or datetime.min, reverse=True)
    top = lines[: settings.recent_activity_max_lines]
    body = _section_title(lang) + "".join(
        f"\n- {_format_date(lang, at)}：{text}" for at, text in top
    )
    return body + "\n\n请勿逐条复述以上动态，仅作背景感知。"


async def _resolve_peer_names(post_ids: list[Any]) -> dict[Any, str]:
    """评论所在动态的作者名（对齐 Java resolvePeerName：CHARACTER→角色名，否则「用户」）。"""
    if not post_ids:
        return {}
    placeholders = ", ".join(["%s"] * len(post_ids))
    rows = await fetch_all(
        f"SELECT id, author_type, character_id FROM moments_post WHERE id IN ({placeholders})",
        tuple(post_ids),
    )
    names: dict[Any, str] = {}
    character_ids = [
        r["character_id"] for r in rows
        if str(r.get("author_type") or "").upper() == "CHARACTER" and r.get("character_id")
    ]
    char_names: dict[Any, str] = {}
    if character_ids:
        char_placeholders = ", ".join(["%s"] * len(character_ids))
        chars = await fetch_all(
            f"SELECT id, name FROM `character` WHERE id IN ({char_placeholders})",
            tuple(character_ids),
        )
        char_names = {c["id"]: (c.get("name") or "").strip() for c in chars}
    for row in rows:
        if str(row.get("author_type") or "").upper() == "CHARACTER":
            names[row["id"]] = char_names.get(row.get("character_id")) or "某人"
        else:
            names[row["id"]] = "用户"
    return names


async def format_for_prompt(user_id: int, character_id: int, lang: str) -> str:
    """带 Redis 缓存（1 小时）的近况块，对齐 Java `formatForPrompt`。"""
    settings = get_settings()
    key = f"{ACTIVITY_KEY_PREFIX}{user_id}:{character_id}"
    cached = await cache_get_json(key)
    if isinstance(cached, str):
        return cached
    formatted = await build_recent_activity(user_id, character_id, lang)
    await cache_put_json(key, formatted, settings.recent_activity_cache_hours * 3600)
    return formatted


async def handle_get_my_recent_life(arguments: dict[str, Any]) -> str:
    user_id = (arguments or {}).get("_user_id")
    character_id = (arguments or {}).get("_character_id")
    lang = (arguments or {}).get("_language") or "zh"
    if not user_id or not character_id:
        return "（当前没有可查询的角色上下文）"
    try:
        block = await format_for_prompt(int(user_id), int(character_id), str(lang))
    except Exception as exc:  # noqa: BLE001 - 数据层失败降级为提示语
        logger.warning("get_my_recent_life failed: %s", exc)
        return "（最近动态暂时查不到）"
    if not block.strip():
        logger.debug("get_my_recent_life empty: userId=%s, characterId=%s", user_id, character_id)
        return "（最近几天没有日记或动态记录）"
    logger.info("get_my_recent_life hit: userId=%s, characterId=%s, chars=%d",
                user_id, character_id, len(block))
    return block


RECENT_ACTIVITY_TOOL = ToolSpec(
    name="get_my_recent_life",
    description=(
        "查询「我」（当前角色）最近的生活动态：近几天写的日记、发的朋友圈、评论摘要。"
        "当用户问你最近在干嘛、上次日记写了什么、有没有发动态、最近过得怎样时调用。"
        "日常寒暄、问天气/时间、不涉及近况时不要调用。"
    ),
    parameters={"type": "object", "properties": {}, "required": []},
    handler=handle_get_my_recent_life,
)

register(RECENT_ACTIVITY_TOOL)

__all__ = [
    "ACTIVITY_KEY_PREFIX",
    "RECENT_ACTIVITY_TOOL",
    "build_recent_activity",
    "format_for_prompt",
    "handle_get_my_recent_life",
]

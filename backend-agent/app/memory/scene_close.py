"""场景关闭 → episode 落库的编排层。

数据流：
  prompt/assemble 发现卡闲置 > 45min
    → `scene_card.stash_pending(card)`（只暂存，不抽取：这里没有模型 key）
  extract-write（下一次带凭据的请求）
    → 本模块取出快照、按 [startedAt, updatedAt] 取消息、抽 episode、写库

为什么分成两步：组装请求由 Java 发起，只带 prompt 数据与场景名，不带
base_url/api_key；把抽取硬塞在那里只能写规则版"假现场"。而 extract-write
本来就要带凭据，顺手把上一幕结算掉，是唯一不需要新契约的接缝。
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

from app.config import get_settings
from app.db import fetch_all
from app.memory.episode_store import insert_episode
from app.memory.extract_scene import MIN_SCENE_MESSAGES, extract_scene_episodes
from app.context.scene_card import take_pending

logger = logging.getLogger(__name__)

MAX_SCENE_MESSAGES = 120
HISTORY_TTL_SECONDS = 7 * 24 * 3600
HISTORY_LIMIT = 3


def _moment(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


async def load_scene_messages(
    conversation_id: int,
    started_at: datetime | None,
    updated_at: datetime | None,
) -> list[dict[str, Any]]:
    """取这一幕里的消息；时间缺失时退化成最近 40 条（宁可多抽，不可空转）。"""
    if started_at and updated_at:
        rows = await fetch_all(
            "SELECT id, role, content, created_at FROM message "
            "WHERE conversation_id = %s AND created_at >= %s AND created_at <= %s "
            "ORDER BY seq ASC LIMIT %s",
            (conversation_id, started_at, updated_at, MAX_SCENE_MESSAGES),
        )
        return list(rows or [])
    rows = await fetch_all(
        "SELECT id, role, content, created_at FROM message "
        "WHERE conversation_id = %s ORDER BY seq DESC LIMIT %s",
        (conversation_id, min(MAX_SCENE_MESSAGES, 40)),
    )
    return list(reversed(list(rows or [])))


def _source_ids(messages: list[dict[str, Any]], quote: str) -> list[int]:
    """给每条 episode 找回它引用的消息 id（quote 是逐字摘录，取前 12 字做包含匹配）。"""
    probe = "".join(quote.split())[:12]
    if not probe:
        return []
    hits = [
        int(message["id"])
        for message in messages
        if message.get("id") is not None and probe in "".join(str(message.get("content") or "").split())
    ]
    return hits[:5]


async def _remember_closed(card: dict[str, Any]) -> None:
    """关闭过的卡留一份短期档案，便于排查「她为什么没记住那一幕」。"""
    conversation_id = card.get("conversationId")
    if conversation_id in (None, ""):
        return
    try:
        from app.db import redis_client
        import json

        client = await redis_client()
        key = f"scene:hist:{conversation_id}"
        await client.lpush(key, json.dumps(card, ensure_ascii=False))
        await client.ltrim(key, 0, HISTORY_LIMIT - 1)
        await client.expire(key, HISTORY_TTL_SECONDS)
    except Exception as exc:  # noqa: BLE001 - 档案只是诊断，不影响主流程
        logger.debug("scene history write failed: %s", exc)


async def close_pending_scene(
    conversation_id: int | None,
    user_id: int,
    character_id: int,
    *,
    base_url: str | None,
    api_key: str | None,
    model: str | None,
    provider: str | None = None,
    gateway: Any | None = None,
) -> int:
    """把暂存的上一幕结算成 episode；返回新写入条数（0 表示没有可结算的）。"""
    settings = get_settings()
    if not getattr(settings, "scene_extract_enabled", True):
        return 0
    if conversation_id in (None, "") or not base_url or not api_key or not model:
        return 0

    card = await take_pending(conversation_id)
    if not card:
        return 0

    started_at = _moment(card.get("startedAt"))
    updated_at = _moment(card.get("updatedAt"))
    try:
        messages = await load_scene_messages(int(conversation_id), started_at, updated_at)
    except Exception as exc:  # noqa: BLE001 - 取消息失败不阻断记忆写入
        logger.warning("scene messages load failed conv=%s: %s", conversation_id, exc)
        return 0

    if len(messages) < MIN_SCENE_MESSAGES:
        logger.debug("scene too short to extract conv=%s messages=%d", conversation_id, len(messages))
        return 0

    factory = gateway
    if factory is None:
        from app.gateway import get_gateway

        factory = get_gateway()

    episodes = await extract_scene_episodes(
        factory,
        base_url=base_url,
        api_key=api_key,
        model=model,
        messages=messages,
        provider=provider,
    )
    if not episodes:
        return 0

    created = 0
    for episode in episodes:
        try:
            new_id = await insert_episode(
                user_id=user_id,
                character_id=character_id,
                conversation_id=conversation_id,
                scene=card,
                summary=episode.summary,
                quote=episode.quote,
                event_time=episode.event_time or updated_at,
                valence=episode.valence,
                arousal=episode.arousal,
                open_loop=episode.open_loop,
                source_msg_ids=_source_ids(messages, episode.quote),
            )
        except Exception as exc:  # noqa: BLE001 - 单条失败不拖垮整幕
            logger.warning("episode insert failed conv=%s: %s", conversation_id, exc)
            continue
        if new_id:
            created += 1

    await _remember_closed(card)
    logger.info("scene closed conv=%s episodes=%d stored=%d", conversation_id, len(episodes), created)
    return created


__all__ = ["MAX_SCENE_MESSAGES", "close_pending_scene", "load_scene_messages"]

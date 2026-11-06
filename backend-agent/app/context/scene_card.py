"""SceneCard：一轮对话「现在在聊什么」的对象（架构文档 §4.4）。

为什么需要它：上下文与长期记忆各自猜「此刻相关」——记忆按 importance 取
top20，历史按条数取最近 32 条，两边都不知道用户这句话在说什么。场景卡是
两边共用的锚：注入时它是一段"当前场景"，检索时它是 query，抽取时它是
边界，关闭时它是 episode 的骨架。

实现取舍：先用规则更新（零成本、可预测），只在话题漂移或每 6 轮时标记
`drift=True` 交给小模型刷新；卡存 Redis 6 小时（`scene:{conversationId}`），
丢了就从最近 12 条重建，不让它成为单点。
"""

from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from typing import Any

from app.db import cache_get_json, cache_put_json

logger = logging.getLogger(__name__)

SCENE_TTL_SECONDS = 21600
MAX_BEATS = 4
MAX_OPEN_LOOPS = 3
MAX_CALLBACKS = 3
MAX_AVOID = 3
MODEL_REFRESH_TURNS = 6

_CJK = re.compile(r"[\u4e00-\u9fff]")

# 情绪词只做"此刻"的粗分类：场景卡不负责情绪强度（那是 character_state 的事）。
_MOOD_WORDS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("低落", ("难过", "伤心", "委屈", "想哭", "失落", "沮丧")),
    ("烦躁", ("生气", "烦", "讨厌", "气死", "恼火")),
    ("疲惫", ("累", "困", "加班", "没力气", "撑不住")),
    ("开心", ("开心", "高兴", "太好了", "哈哈", "兴奋")),
    ("不安", ("害怕", "担心", "焦虑", "紧张", "睡不着")),
)


def scene_key(conversation_id: int | str) -> str:
    return f"scene:{conversation_id}"


def _now_iso(now: datetime | None = None) -> str:
    moment = now or datetime.now(timezone.utc)
    return moment.astimezone(timezone.utc).isoformat(timespec="seconds")


def new_card(conversation_id: int | str | None = None, now: datetime | None = None) -> dict[str, Any]:
    return {
        "version": 1,
        "conversationId": conversation_id,
        "updatedAt": _now_iso(now),
        "turnFrom": 0,
        "turnTo": 0,
        "topic": "",
        "mood": "",
        "beats": [],
        "openLoops": [],
        "callbacks": [],
        "avoid": [],
        "drift": False,
    }


def _clip_list(value: Any, limit: int) -> list[str]:
    if not isinstance(value, list):
        return []
    out: list[str] = []
    for item in value:
        text = str(item or "").strip()
        if text and text not in out:
            out.append(text)
    return out[-limit:]


def normalize_card(raw: Any, conversation_id: int | str | None = None) -> dict[str, Any]:
    """把任意来源（Redis / 模型 / 旧版本）的卡收敛成合法结构。"""
    card = new_card(conversation_id)
    if not isinstance(raw, dict):
        return card
    card.update({
        "version": int(raw.get("version") or 1),
        "conversationId": raw.get("conversationId", conversation_id),
        "updatedAt": str(raw.get("updatedAt") or card["updatedAt"]),
        "turnFrom": int(raw.get("turnFrom") or 0),
        "turnTo": int(raw.get("turnTo") or 0),
        "topic": str(raw.get("topic") or "").strip()[:80],
        "mood": str(raw.get("mood") or "").strip()[:16],
        "beats": _clip_list(raw.get("beats"), MAX_BEATS),
        "openLoops": _clip_list(raw.get("openLoops"), MAX_OPEN_LOOPS),
        "callbacks": _clip_list(raw.get("callbacks"), MAX_CALLBACKS),
        "avoid": _clip_list(raw.get("avoid"), MAX_AVOID),
        "drift": bool(raw.get("drift")),
    })
    return card


def _bigrams(text: str) -> set[str]:
    clean = re.sub(r"\s+", "", text or "")
    return {clean[i:i + 2] for i in range(len(clean) - 1)} if len(clean) > 1 else set()


def _overlap(a: str, b: str) -> float:
    """`a` 的字符二元组有多大比例出现在 `b` 里。

    用包含度而不是 Jaccard：中文短句里新话题往往只共享少数词（"加班"），
    Jaccard 会被两边长度稀释成 0.05，误判成漂移；
    「这句话里的概念有多少已经在卡里」才是判断"同一件事"的直觉。
    """
    left, right = _bigrams(a), _bigrams(b)
    if not left or not right:
        return 0.0
    return len(left & right) / len(left)


def detect_mood(text: str) -> str:
    for mood, words in _MOOD_WORDS:
        if any(word in (text or "") for word in words):
            return mood
    return ""


def render_card(card: dict[str, Any] | None, max_chars: int = 200) -> str:
    """把卡渲染成注入块；空卡返回空串（不产生「当前场景：无」这种噪音）。

    `max_chars` 默认 200：预算里场景槽是 220 token，中文 1 字≈1 token，
    留 20 token 给分隔符与标题。
    """
    card = normalize_card(card, (card or {}).get("conversationId") if isinstance(card, dict) else None)
    if not any((card["topic"], card["mood"], card["beats"], card["openLoops"],
                card["callbacks"], card["avoid"])):
        return ""

    lines = ["【当前场景】"]
    if card["topic"]:
        lines.append(f"话题：{card['topic']}")
    if card["mood"]:
        lines.append(f"此刻：{card['mood']}")
    if card["beats"]:
        lines.append("刚发生：" + "；".join(card["beats"]))
    if card["openLoops"]:
        lines.append("还没接的话：" + "；".join(card["openLoops"]))
    if card["callbacks"]:
        lines.append("可以回扣：" + "；".join(card["callbacks"]))
    if card["avoid"]:
        lines.append("避免：" + "；".join(card["avoid"]))

    text = "\n".join(lines)
    if len(text) > max_chars:
        text = text[: max_chars - 1].rstrip() + "…"
    return text


def _snippet(text: str, limit: int = 32) -> str:
    clean = re.sub(r"\s+", " ", text or "").strip()
    return clean if len(clean) <= limit else clean[: limit - 1] + "…"


def update_card(card: dict[str, Any] | None, *,
                user_text: str,
                assistant_text: str = "",
                turn: int | None = None,
                now: datetime | None = None) -> dict[str, Any]:
    """用这一轮的用户话与回复，规则化地推进场景卡。"""
    card = normalize_card(card, (card or {}).get("conversationId") if isinstance(card, dict) else None)
    user_text = (user_text or "").strip()
    if turn is not None:
        card["turnTo"] = int(turn)
        if not card["turnFrom"]:
            card["turnFrom"] = int(turn)

    if user_text:
        beat = _snippet(user_text, 40)
        is_new_turn = not card["beats"] or card["beats"][-1] != beat
        if is_new_turn and card["openLoops"]:
            # 上一轮的提问已经由上一轮回复接住；卡里只留本轮的问题。
            # 组装路径拿不到 assistant 文本（Java 只传最后一句话），
            # 所以用「新的一轮用户话」作为清账信号，避免挂着一串早答完的旧问句。
            card["openLoops"] = []
        anchor = f"{card['topic']} {' '.join(card['beats'])}"
        if not card["topic"]:
            card["topic"] = _snippet(user_text, 36)
        elif len(user_text) >= 6 and _overlap(user_text, anchor) < 0.08:
            # 话题漂移：旧话题降级成"可以回扣"，新话题上位。
            # 短句（"嗯"/"好呀"）不算换话题，否则每句附和都会清空上下文。
            old = card["topic"]
            card["callbacks"] = _clip_list([*card["callbacks"], old], MAX_CALLBACKS)
            card["topic"] = _snippet(user_text, 36)
            card["drift"] = True

        if is_new_turn:
            card["beats"] = _clip_list([*card["beats"], beat], MAX_BEATS)

        if user_text.rstrip().endswith(("？", "?")) or "吗" in user_text[-6:]:
            card["openLoops"] = _clip_list(
                [*card["openLoops"], _snippet(user_text, 40)], MAX_OPEN_LOOPS)

        mood = detect_mood(user_text)
        if mood:
            card["mood"] = mood

    # 回复里已经接住的话，从"还没接"里划掉（用包含度而非完全匹配，容错复述）。
    if assistant_text and card["openLoops"]:
        remaining = [loop for loop in card["openLoops"]
                     if _overlap(loop, assistant_text) < 0.15]
        card["openLoops"] = remaining

    card["updatedAt"] = _now_iso(now)
    return card


def needs_model_refresh(card: dict[str, Any], turn: int | None = None) -> bool:
    """是否该让小模型重写一次卡（话题漂移或每 6 轮兜底）。"""
    if card.get("drift"):
        return True
    if turn is None:
        return False
    return turn - int(card.get("turnTo") or 0) >= MODEL_REFRESH_TURNS


async def load_card(conversation_id: int | str | None) -> dict[str, Any] | None:
    if conversation_id in (None, ""):
        return None
    raw = await cache_get_json(scene_key(conversation_id))
    return normalize_card(raw, conversation_id) if raw else None


async def save_card(card: dict[str, Any]) -> None:
    conversation_id = card.get("conversationId")
    if conversation_id in (None, ""):
        return
    payload = normalize_card(card, conversation_id)
    payload["updatedAt"] = _now_iso()
    await cache_put_json(scene_key(conversation_id), payload, SCENE_TTL_SECONDS)


async def clear_card(conversation_id: int | str | None) -> None:
    """清空会话时一并删卡；失败不阻断业务。"""
    if conversation_id in (None, ""):
        return
    try:
        from app.db import redis_client

        client = await redis_client()
        await client.delete(scene_key(conversation_id))
    except Exception as exc:  # noqa: BLE001 - 清缓存失败不回滚业务
        logger.debug("scene card clear failed key=%s: %s", conversation_id, exc)


__all__ = [
    "MAX_BEATS",
    "MAX_CALLBACKS",
    "MAX_OPEN_LOOPS",
    "MODEL_REFRESH_TURNS",
    "SCENE_TTL_SECONDS",
    "clear_card",
    "detect_mood",
    "load_card",
    "needs_model_refresh",
    "new_card",
    "normalize_card",
    "render_card",
    "save_card",
    "scene_key",
    "update_card",
]

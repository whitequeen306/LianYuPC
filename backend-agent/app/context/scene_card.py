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
# 闲置多久算「上一场聊完了」：20 分钟。
#
# 原来是 45 分钟，生产上几乎不触发：用户常常隔半小时回来接着说，
# 却因为没跨过 45 分钟门槛，整晚只有一张卡、一次也结算不了
# （上线一天后 episode 只有 3 条）。20 分钟对"去忙一下回来"仍算短，
# 但足以把「晚上聊工作」和「睡前聊别的」分成两幕。
SCENE_IDLE_SECONDS = 20 * 60
# 一幕最多 40 轮：连聊两小时不歇的情况靠轮次兜底，否则这一场永远不结束。
SCENE_MAX_TURNS = 40
# 关闭快照最多留 7 天：它的用途是「抽取失败后还能重跑」，不是长期档案。
PENDING_TTL_SECONDS = 7 * 24 * 3600
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
    stamp = _now_iso(now)
    return {
        "version": 1,
        "conversationId": conversation_id,
        # startedAt 是这一幕的起点，关闭时用它圈定要抽取的消息区间。
        "startedAt": stamp,
        "updatedAt": stamp,
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
        "startedAt": str(raw.get("startedAt") or raw.get("updatedAt") or card["startedAt"]),
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
                now: datetime | None = None,
                track_questions: bool = True) -> dict[str, Any]:
    """用这一轮的用户话与回复，规则化地推进场景卡。

    `track_questions=False`：组装路径专用——那句问话正是她**马上要回答**的，
    记成"还没接的话"会让 prompt 里出现「还没接：你刚问的这句」这种自相矛盾。
    """
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

        is_question = user_text.rstrip().endswith(("？", "?")) or "吗" in user_text[-6:]
        if track_questions and is_question:
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


def _parse_iso(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment


def is_stale(card: dict[str, Any] | None, now: datetime | None = None) -> bool:
    """卡是否该结束（闲置超时或轮次超限）；话题漂移由 `topic_drifted` 单独判。"""
    return close_reason(card, now=now) is not None


def turn_count(card: dict[str, Any] | None) -> int:
    """这一幕已经聊了几轮（卡里没记轮次时返回 0）。"""
    if not isinstance(card, dict):
        return 0
    try:
        first = int(card.get("turnFrom") or 0)
        last = int(card.get("turnTo") or 0)
    except (TypeError, ValueError):
        return 0
    return max(0, last - first + 1) if first else 0


def _idle_seconds(card: dict[str, Any], now: datetime | None) -> float | None:
    moment = _parse_iso(card.get("updatedAt"))
    if moment is None:
        return None
    reference = now or datetime.now(timezone.utc)
    if reference.tzinfo is None:
        reference = reference.replace(tzinfo=timezone.utc)
    return (reference - moment).total_seconds()


def topic_drifted(card: dict[str, Any] | None, text: str) -> bool:
    """这句话是不是已经换了个话题（与卡里的话题 + 刚发生的事几乎不重叠）。

    与 `update_card` 内部用的判据一致（包含度 < 0.08 且长度 ≥ 6），
    只是搬到「先判要不要收场」的位置：一旦漂移就先把上一幕结算掉，
    再从新话题起一张干净的卡，而不是在同一张卡里覆盖话题。
    """
    if not isinstance(card, dict):
        return False
    topic = str(card.get("topic") or "").strip()
    clean = (text or "").strip()
    if not topic or len(clean) < 6:
        return False
    anchor = f"{topic} {' '.join(card.get('beats') or [])}"
    return _overlap(clean, anchor) < 0.08


def close_reason(card: dict[str, Any] | None, text: str | None = None,
                 now: datetime | None = None) -> str | None:
    """这一幕是否该收场，以及为什么：`idle` / `turns` / `drift` / None。

    收场理由要能落日志：生产上「为什么一整天没结算」只能靠它定位。
    """
    if not isinstance(card, dict):
        return None
    idle = _idle_seconds(card, now)
    if idle is not None and idle >= SCENE_IDLE_SECONDS:
        return "idle"
    if turn_count(card) >= SCENE_MAX_TURNS:
        return "turns"
    if text and topic_drifted(card, text):
        return "drift"
    return None


def _closed_snapshot(card: dict[str, Any]) -> dict[str, Any]:
    """给抽取用的最小快照：不含运行期字段，落 Redis 待处理。"""
    return {
        "conversationId": card.get("conversationId"),
        "startedAt": card.get("startedAt"),
        "updatedAt": card.get("updatedAt"),
        "topic": card.get("topic"),
        "mood": card.get("mood"),
        "beats": list(card.get("beats") or []),
        "openLoops": list(card.get("openLoops") or []),
    }


def pending_key(conversation_id: int | str) -> str:
    return f"scene:pending:{conversation_id}"


async def stash_pending(card: dict[str, Any]) -> None:
    """把已关闭的场景暂存起来，等下一次带模型凭据的请求来抽取。

    为什么不在组装路径直接抽取：组装请求里没有模型凭据（Java 只在
    extract-write 时递 base_url/api_key），硬抽只能写规则版假 episode。
    """
    conversation_id = card.get("conversationId")
    if conversation_id in (None, ""):
        return
    try:
        await cache_put_json(pending_key(conversation_id), _closed_snapshot(card), PENDING_TTL_SECONDS)
    except Exception as exc:  # noqa: BLE001 - 暂存失败不影响本轮组装
        logger.debug("scene pending stash failed key=%s: %s", conversation_id, exc)


async def take_pending(conversation_id: int | str | None) -> dict[str, Any] | None:
    if conversation_id in (None, ""):
        return None
    raw = await cache_get_json(pending_key(conversation_id))
    if not isinstance(raw, dict):
        return None
    try:
        from app.db import redis_client

        client = await redis_client()
        await client.delete(pending_key(conversation_id))
    except Exception as exc:  # noqa: BLE001
        logger.debug("scene pending delete failed key=%s: %s", conversation_id, exc)
    return raw


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
    "PENDING_TTL_SECONDS",
    "SCENE_IDLE_SECONDS",
    "SCENE_MAX_TURNS",
    "SCENE_TTL_SECONDS",
    "clear_card",
    "close_reason",
    "detect_mood",
    "is_stale",
    "load_card",
    "needs_model_refresh",
    "new_card",
    "normalize_card",
    "pending_key",
    "render_card",
    "save_card",
    "scene_key",
    "stash_pending",
    "take_pending",
    "topic_drifted",
    "turn_count",
    "update_card",
]

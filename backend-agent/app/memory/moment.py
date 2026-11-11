"""通话中「这一刻」的轻量记忆：只写 episode，不写 meta。

为什么只写 episode：电话里说的多是近况与情绪由来，它们不构成「她是谁 / 你是谁」
的稳定事实（那是 `memory_meta` 的职责），但丢掉之后下一通就接不上话题。
episode 天然带一句原话（quote），召回时可以整句带回现场。

为什么判定在 Python：要不要记、记成什么，都是语言判断；Java 只把这一轮的
用户原话与角色回复递过来，不参与判定，也不拼任何 prompt。
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any

from app.gateway import ModelGateway

logger = logging.getLogger(__name__)

# 太短的话（"嗯""好"）没有可记的信息量；Java 侧也有同样的门槛，这里兜底。
MIN_USER_CHARS = 8

MOMENT_SYSTEM_PROMPT = """你在判断一通电话里，用户说的这段话有没有值得长期记住的事。
规则：
1) 只记「关于用户自己」的内容：近况、计划、情绪由来、在乎的人或物、明确说过的事实；
2) 寒暄、天气、玩笑、提问，以及对角色的评价（"你好可爱"）都不算；
3) 值得记时给出 summary（第三人称，<=60 字）与 quote（用户原话里逐字摘录的一句，
   不许改写、不许拼接、不许加标点）；
4) 同时给出 valence（-1~1，这段话的情绪正负）与 arousal（0~1，激动程度）；
5) openLoop 是这段话里没说完、下次可以接的悬念，没有就空串；
6) 只输出 JSON，不要 markdown：
{"worth":true,"summary":"...","quote":"...","valence":0.2,"arousal":0.3,"openLoop":""}
不值得记就输出 {"worth":false}。"""


def _extract_json_object(text: str) -> str | None:
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start:end + 1]
    return None


def _clamp(value: Any, low: float, high: float) -> float | None:
    if value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return max(low, min(high, number))


def _pick_quote(raw: Any, user_text: str) -> str:
    """quote 必须是**逐字的用户原话**。

    模型偶尔会顺手润色（改标点、合并两句），那就不再是证据。这种情况退回整句
    用户文本 —— 它仍然逐字，只是长一点，不能因此把这一幕丢掉。
    """
    candidate = str(raw or "").strip()
    if candidate and candidate in user_text:
        return candidate
    return user_text.strip()


def parse_verdict(raw: str, *, user_text: str) -> dict[str, Any] | None:
    """解析模型判定；不值得记 / 解析失败返回 None（失败一律不写）。"""
    if not raw or not raw.strip():
        return None
    json_text = _extract_json_object(raw.strip())
    if not json_text:
        return None
    try:
        root = json.loads(json_text)
    except json.JSONDecodeError:
        logger.debug("voice moment json parse failed")
        return None
    if not isinstance(root, dict) or root.get("worth") is not True:
        return None

    quote = _pick_quote(root.get("quote"), user_text)
    if not quote:
        return None
    summary = str(root.get("summary") or "").strip() or quote
    open_loop = str(root.get("openLoop") or root.get("open_loop") or "").strip()
    return {
        "summary": summary,
        "quote": quote,
        "valence": _clamp(root.get("valence"), -1.0, 1.0),
        "arousal": _clamp(root.get("arousal"), 0.0, 1.0),
        "open_loop": open_loop or None,
    }


async def judge_moment(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    user_text: str,
    reply_text: str = "",
    provider: str | None = None,
) -> dict[str, Any] | None:
    """让模型判断这一段值不值得记；调用失败按「不值得记」处理。"""
    context = "用户说：" + user_text.strip()
    reply = (reply_text or "").strip()
    if reply:
        context += "\n角色回：" + reply
    try:
        result = await gateway.chat_messages(
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=[
                {"role": "system", "content": MOMENT_SYSTEM_PROMPT},
                {"role": "user", "content": context},
            ],
            temperature=0.1,
            max_tokens=300,
            provider=provider,
            lane_name="memory-moment",
        )
    except Exception as exc:  # noqa: BLE001 - 判定失败只是不记，不能影响通话
        logger.warning("voice moment judge failed: %s", exc)
        return None
    return parse_verdict(result.content or "", user_text=user_text)


async def record_moment(
    gateway: ModelGateway,
    *,
    user_id: int,
    character_id: int,
    user_text: str,
    reply_text: str = "",
    conversation_id: int | None = None,
    source_msg_ids: list[int] | None = None,
    base_url: str | None = None,
    api_key: str | None = None,
    model: str | None = None,
    provider: str | None = None,
) -> int:
    """判定并写入一条 episode，返回新行 id；不写/重复/失败都返回 0。

    只走 `episode_store.insert_episode`（内部再走 `app.write` 白名单）：
    通话写入绝不碰 `memory_meta`，避免把临时话题升级成长期事实。
    """
    text = (user_text or "").strip()
    if len(text) < MIN_USER_CHARS:
        return 0
    if not (base_url and api_key and model):
        return 0

    verdict = await judge_moment(
        gateway,
        base_url=base_url,
        api_key=api_key,
        model=model,
        user_text=text,
        reply_text=reply_text,
        provider=provider,
    )
    if verdict is None:
        return 0

    from app.memory.episode_store import insert_episode

    try:
        return await insert_episode(
            user_id=user_id,
            character_id=character_id,
            conversation_id=conversation_id,
            # 场景标记让召回能区分「电话里说的」与「打字说的」
            scene={"scene": "VOICE_CALL"},
            summary=verdict["summary"],
            quote=verdict["quote"],
            event_time=datetime.now(),
            valence=verdict.get("valence"),
            arousal=verdict.get("arousal"),
            open_loop=verdict.get("open_loop"),
            source_msg_ids=source_msg_ids or [],
        )
    except Exception as exc:  # noqa: BLE001 - 写入失败不能打断通话
        logger.warning("voice moment write failed: %s", exc)
        return 0


__all__ = [
    "MIN_USER_CHARS",
    "MOMENT_SYSTEM_PROMPT",
    "judge_moment",
    "parse_verdict",
    "record_moment",
]

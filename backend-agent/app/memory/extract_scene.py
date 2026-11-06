"""场景关闭时的 episode 抽取（LLM）。

与 `extract.py` 的分工：
- `extract.py` 回答「这个人是谁」——画像槽位、偏好、禁忌，写 `memory_meta`；
- 本模块回答「刚才发生了什么」——一段带原话的现场，写 `memory_episode`。

为什么 episode 必须带原话：事实可以推断，现场不能编造。召回时先挑 episode、
再回原文取 quote，模型才有「她那天是这么说的」这种可引用的东西；
只有摘要的抽取式记忆在 Fidelity Before Structure 的对照里比原文低 15.9 个点。

触发点：`/agent/prompt/assemble` 发现场景卡闲置超过 45 分钟时，把卡快照
塞进 `scene:pending:{conversationId}`；下一次带模型凭据的 extract-write
请求把它取出来，用同一批消息抽 episode。这样组装路径不需要持有模型 key。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable

from app.gateway import ModelGateway

logger = logging.getLogger(__name__)

SCENE_EXTRACT_PROMPT = """你是「场景档案员」。下面是一段已经结束的聊天记录，请把它整理成几条「发生了什么」，供角色以后回忆。
规则：
1) 只记录**用户**说过或做过的事，以及双方共同经历的事；不要记录角色自己的台词；
2) 每条必须附一句**用户原话**作为证据（quote，逐字摘录，不改写、不拼接）；找不到原话就不要输出这条；
3) summary 用第三人称一句话概括（不超过 60 字），例如「她加班到很晚，回家路上买了关东煮」；
4) eventTime 用记录里出现的时间（ISO 日期或日期时间），拿不准就填 null；
5) valence 情感效价 -1~1（负→正），arousal 唤醒度 0~1；拿不准填 null；
6) 如果用户留下了一个「以后要接的话头」（约定、请求、未完成的计划），填 openLoop，否则 null；
7) 最多 6 条，按重要性排序；寒暄、玩笑、重复内容不要；
8) 另用 impression 写一句**角色第一人称**的感受（不超过 40 字），像日记落款，
   不是事实复述，也不要重复下面任何 summary；没有感觉就填 null；
9) 只输出 JSON，不要 markdown：
{"impression":"...","episodes":[{"summary":"...","quote":"...","eventTime":null,"valence":-0.4,"arousal":0.6,"openLoop":null}]}
若没有值得留下的内容，返回 {"episodes":[]}。"""

MAX_TRANSCRIPT_MESSAGES = 40
MAX_MESSAGE_CHARS = 200
MAX_EPISODES = 6
MIN_SCENE_MESSAGES = 4


@dataclass(frozen=True)
class SceneResult:
    """一次场景结算的产物：可落库的现场 + 一句角色自己的感受。"""

    episodes: list["SceneEpisode"]
    impression: str | None = None


@dataclass(frozen=True)
class SceneEpisode:
    summary: str
    quote: str
    event_time: datetime | None = None
    valence: float | None = None
    arousal: float | None = None
    open_loop: str | None = None


def _clip(text: Any, limit: int) -> str:
    return ("" if text is None else str(text)).strip()[:limit]


def _moment(value: Any) -> datetime | None:
    if not value:
        return None
    raw = str(value).strip()
    if not raw or raw.lower() in {"null", "none", "unknown"}:
        return None
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _number(value: Any, low: float, high: float) -> float | None:
    if value in (None, "", "null"):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return max(low, min(high, number))


def build_transcript(messages: Iterable[dict[str, Any]]) -> str:
    """把消息拼成带时间与角色的记录；只保留 user/assistant，system 噪声不要。"""
    lines: list[str] = []
    for message in list(messages)[-MAX_TRANSCRIPT_MESSAGES:]:
        role = str(message.get("role") or "").upper()
        if role not in {"USER", "ASSISTANT"}:
            continue
        text = " ".join(str(message.get("content") or "").split())
        if not text:
            continue
        text = text[:MAX_MESSAGE_CHARS]
        stamp = message.get("createdAt") or message.get("created_at") or ""
        who = "用户" if role == "USER" else "角色"
        prefix = f"[{stamp}] " if stamp else ""
        lines.append(f"{prefix}{who}: {text}")
    return "\n".join(lines)


def _payload(text: str) -> dict[str, Any]:
    raw = (text or "").strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        if raw.lower().startswith("json"):
            raw = raw[4:]
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return {}
    try:
        parsed = json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        logger.debug("scene extract: invalid json payload")
        return {}
    return parsed if isinstance(parsed, dict) else {}


def parse_impression(text: str) -> str | None:
    """角色第一人称的收尾感受；太短或只是复述摘要时丢弃。"""
    value = _clip(_payload(text).get("impression"), 120).strip("「」\"'“”")
    return value if len(value) >= 6 else None


def parse_scene_result(text: str) -> SceneResult:
    return SceneResult(episodes=parse_episodes(text), impression=parse_impression(text))


def parse_episodes(text: str) -> list[SceneEpisode]:
    """解析模型输出；没有 quote 的条目直接丢掉（不允许无证据的现场）。"""
    payload = _payload(text)
    nodes = payload.get("episodes")
    if not isinstance(nodes, list):
        return []
    episodes: list[SceneEpisode] = []
    for node in nodes:
        if not isinstance(node, dict):
            continue
        quote = _clip(node.get("quote"), 600)
        summary = _clip(node.get("summary"), 500) or quote[:60]
        if not quote or not summary:
            continue
        episodes.append(SceneEpisode(
            summary=summary,
            quote=quote,
            event_time=_moment(node.get("eventTime")),
            valence=_number(node.get("valence"), -1.0, 1.0),
            arousal=_number(node.get("arousal"), 0.0, 1.0),
            open_loop=_clip(node.get("openLoop"), 200) or None,
        ))
    return episodes[:MAX_EPISODES]


async def extract_scene(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    provider: str | None = None,
) -> SceneResult:
    """一次调用同时拿 episode 与一句 impression（多问一句不加钱）。"""
    transcript = build_transcript(messages)
    if not transcript:
        return SceneResult(episodes=[])
    try:
        result = await gateway.chat_messages(
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=[
                {"role": "system", "content": SCENE_EXTRACT_PROMPT},
                {"role": "user", "content": "这段聊天：\n" + transcript},
            ],
            temperature=0.2,
            max_tokens=900,
            provider=provider,
            lane_name="background",
        )
    except Exception as exc:  # noqa: BLE001 - 抽取失败只丢这一幕
        logger.warning("scene extraction failed: %s", exc)
        return SceneResult(episodes=[])
    return parse_scene_result(result.content or "")


async def extract_scene_episodes(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    provider: str | None = None,
) -> list[SceneEpisode]:
    """兼容入口：只要 episode 列表。"""
    result = await extract_scene(
        gateway,
        base_url=base_url,
        api_key=api_key,
        model=model,
        messages=messages,
        provider=provider,
    )
    return result.episodes


__all__ = [
    "MAX_EPISODES",
    "MAX_TRANSCRIPT_MESSAGES",
    "MIN_SCENE_MESSAGES",
    "SCENE_EXTRACT_PROMPT",
    "SceneResult",
    "extract_scene",
    "parse_impression",
    "parse_scene_result",
    "SceneEpisode",
    "build_transcript",
    "extract_scene_episodes",
    "parse_episodes",
]

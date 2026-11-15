"""表情包发送前的视觉复核：让模型先看一眼图，再决定发哪张。

为什么要有这一层：词表只读文字，读不出「这张图画的是什么」。角色手里那张 `speechless`
可能是一张翻白眼的脸，也可能是随手糊的一团——按标签发出去，用户看到的就是驴唇不对
马嘴的图。所以把候选图真的交给视觉模型，让它按当前对话判断「发哪张 / 一张都别发」。

边界：**有哪些图**是 Java 的事实（`character_sticker` + 对象存储字节），**发不发、
发哪张**在这里判。复核只是锦上添花：模型超时、格式不对、没配视觉模型时一律退回词表
（`app/stickers.py`），不能因为复核挂了就让用户收不到本来该发的那张。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from app.gateway import ImagePart, ModelGateway, TextPart, extract_json_object
from app.stickers import pick_sticker, sticker_material, wants_sticker
from app.vision import vision_thinking_extra

logger = logging.getLogger(__name__)

REVIEW_SYSTEM = (
    "你在替一个聊天角色把关表情包。用户会给你当前的一来一回，以及这个角色手里的候选图"
    "（按顺序编号）。你的任务不是描述图片，而是判断此刻发哪一张不会显得答非所问："
    "情绪对得上、场合不别扭。宁愿一张都不发，也不要发一张和对话无关的图。"
)

REVIEW_INSTRUCTION = """当前对话
用户：{user_text}
角色：{reply}

这个角色手里有 {count} 张表情包，按顺序编号如下（图按同样顺序附在后面）：
{legend}

只输出一个 JSON 对象，不要解释、不要代码块：
{{"send": true, "index": 3, "reason": "一句话理由"}}
一张都不合适时输出：{{"send": false, "reason": "一句话理由"}}"""


@dataclass(frozen=True)
class ReviewDecision:
    """复核结论。`url` 为 None 表示模型明确说「别发」。"""

    url: str | None
    reason: str
    explicit_request: bool


def _b64(sticker: Mapping[str, Any]) -> str:
    value = sticker.get("imageBase64") or sticker.get("image_base64") or ""
    return value.strip() if isinstance(value, str) else ""


def _mime(sticker: Mapping[str, Any]) -> str:
    value = sticker.get("imageMime") or sticker.get("image_mime") or ""
    if isinstance(value, str) and value.strip().startswith("image/"):
        return value.strip()
    # 表情包统一按 PNG 送：调用方给的是对象存储里的原始字节，PNG 是这群图的主格式，
    # 视觉模型对 data:image/png 的兼容性最好；GIF/WebP 在 data URL 里也按声明解码。
    return "image/png"


def _parse_decision(text: str) -> tuple[bool, int | None, str]:
    """从模型输出里抠出 JSON。复用 `extract_json_object`（<json> / ```json / 裸 JSON 三形态）。"""
    payload = extract_json_object(text or "")
    if not payload:
        return False, None, ""
    reason = str(payload.get("reason") or "")
    if not payload.get("send"):
        return False, None, reason
    try:
        index = int(payload.get("index"))
    except (TypeError, ValueError):
        return False, None, ""
    return True, index, reason


async def review_sticker(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    user_text: str | None,
    reply: str,
    candidates: Sequence[Mapping[str, Any]],
    last_url: str | None = None,
    max_candidates: int = 8,
    max_tokens: int = 160,
) -> ReviewDecision | None:
    """把带字节的候选图交给视觉模型复核。

    返回 None 表示「复核没做成」（没配视觉模型 / 没有图字节 / 调用或解析失败），
    调用方应当退回词表；返回 ReviewDecision(url=None) 才是「模型看过，说别发」。
    """
    with_images = [(sticker, _b64(sticker)) for sticker in candidates if _b64(sticker)]
    if not with_images or not model or not api_key or not base_url:
        return None

    picked = with_images[: max(1, max_candidates)]
    legend = []
    for number, (sticker, _) in enumerate(picked):
        emotion = str(sticker.get("emotion") or "").strip()
        tags = str(sticker.get("tags") or "").strip()
        label = " / ".join(part for part in (emotion, tags) if part)
        legend.append(f"#{number}" + (f"（角色标注：{label}）" if label else ""))

    instruction = REVIEW_INSTRUCTION.format(
        user_text=(user_text or "（无）").strip() or "（无）",
        reply=(reply or "（无）").strip() or "（无）",
        count=len(picked),
        legend="\n".join(legend),
    )
    parts: list[Any] = [TextPart(text=instruction)]
    for sticker, encoded in picked:
        parts.append(ImagePart(png_base64=encoded, mime=_mime(sticker)))

    try:
        result = await gateway.chat(
            base_url=base_url,
            api_key=api_key,
            model=model,
            system=REVIEW_SYSTEM,
            parts=parts,
            temperature=0.2,
            max_tokens=max_tokens,
            extra=vision_thinking_extra(),
        )
    except Exception as exc:  # noqa: BLE001 - 复核失败一律退回词表
        logger.warning("sticker review call failed: %r", exc)
        return None

    send, index, reason = _parse_decision(getattr(result, "content", "") or "")
    if not send or index is None or not (0 <= index < len(picked)):
        return ReviewDecision(url=None, reason=reason or "model_declined", explicit_request=False)
    url = str(picked[index][0].get("url") or "").strip()
    if not url:
        return None
    if last_url and url == last_url:
        # 刚发过同一张：换一张才有意义，没得换就干脆不发
        for sticker, _ in picked:
            other = str(sticker.get("url") or "").strip()
            if other and other != last_url:
                return ReviewDecision(url=other, reason=reason or "avoid_repeat", explicit_request=False)
        return ReviewDecision(url=None, reason="only_repeat_available", explicit_request=False)
    return ReviewDecision(url=url, reason=reason, explicit_request=False)


async def choose_sticker(
    gateway: ModelGateway | None,
    *,
    base_url: str = "",
    api_key: str = "",
    model: str = "",
    user_text: str | None,
    reply: str,
    candidates: Sequence[Mapping[str, Any]] | None,
    last_url: str | None = None,
    review_enabled: bool = True,
    max_candidates: int = 8,
    max_tokens: int = 160,
) -> str | None:
    """决定这一轮发哪张表情包（None = 不发）。

    顺序：用户点名要图 → 复核（若可用）→ 词表兜底。用户点名时即使模型说「别发」，
    也退回词表给他发一张：他已经开口要了，装没听见最糟。
    """
    stickers = list(candidates or [])
    if not stickers:
        return None
    explicit = wants_sticker(user_text)
    material = sticker_material(user_text, reply)

    if review_enabled and gateway is not None:
        decision = await review_sticker(
            gateway,
            base_url=base_url,
            api_key=api_key,
            model=model,
            user_text=user_text,
            reply=reply,
            candidates=stickers,
            last_url=last_url,
            max_candidates=max_candidates,
            max_tokens=max_tokens,
        )
        if decision is not None:
            if decision.url:
                logger.info(
                    "sticker.chosen source=review url=%s explicit=%s reason=%s",
                    decision.url, explicit, decision.reason,
                )
                return decision.url
            if not explicit:
                logger.info("sticker.skipped source=review explicit=False reason=%s", decision.reason)
                return None
            logger.info("sticker review declined but user asked explicitly: %s", decision.reason)

    chosen = pick_sticker(material, stickers, last_url=last_url, explicit=explicit)
    logger.info(
        "sticker.chosen source=keywords url=%s explicit=%s review=%s",
        chosen, explicit, "on" if review_enabled and gateway is not None else "off",
    )
    return chosen

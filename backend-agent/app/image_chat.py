"""图片消息回合：两段式（VL 分析 → 文本生成），仅接受 base64。

对齐 Java `VisionChatService.chatImageStream` 的实际语义：它不是逐 token 推流，
而是阻塞完成「识图 + 文本生成」后一次性下发整段内容，再由
`SseChatStreamHelper` 发 pieces 与 `[DONE]`。

取图职责留在 Java：`VisionMessageBuilder` 用 FileStorageService.extractObjectKey +
readObjectBytes 从对象存储读字节，再以 ByteArrayResource 交给视觉模型。
Python 因此只收 base64，不引入新的 URL 下载与 SSRF 面。

返回 {content, imageDescription}：
- imageDescription 供 Java 的 callback.onVisionComplete(...) 落库占位文案
- content 由 Java 作为单个 chunk 下发
"""

from __future__ import annotations

import logging

from app.config import get_settings
from app.gateway import ImagePart, ModelGateway, ModelGatewayError
from app.vision import VISION_ANALYSIS_JSON_INSTRUCTION, parse_vision_analysis

logger = logging.getLogger(__name__)

IMAGE_CONTEXT_TEMPLATE = (
    "【用户发送了一张图片】\n"
    "图片内容：{description}\n"
    "对方意图（推测）：{intent}\n"
    "请据此自然地继续对话，不要复述这段描述。"
)


def build_image_context(description: str, sub_intent: str | None) -> str:
    return IMAGE_CONTEXT_TEMPLATE.format(
        description=(description or "").strip(),
        intent=(sub_intent or "未明确").strip(),
    )


async def chat_with_image(
    gateway: ModelGateway,
    *,
    image_png_base64: str,
    vision_base_url: str,
    vision_api_key: str,
    vision_model: str,
    base_url: str,
    api_key: str,
    model: str,
    system: str,
    messages: list[dict[str, str]],
    temperature: float,
    max_tokens: int,
    provider: str | None = None,
) -> dict[str, object]:
    """返回 {content, imageDescription} 或 {error}。"""
    settings = get_settings()
    if not image_png_base64:
        return {"error": "missing_image"}

    # Stage 1：视觉 JSON 分析（temperature 0.1，与 Java 一致）
    try:
        vision_result = await gateway.chat(
            base_url=vision_base_url,
            api_key=vision_api_key,
            model=vision_model,
            system=VISION_ANALYSIS_JSON_INSTRUCTION,
            parts=[ImagePart(png_base64=image_png_base64)],
            temperature=0.1,
            max_tokens=settings.multimodal_describe_max_tokens,
        )
        analysis = parse_vision_analysis(vision_result.content or "")
    except Exception as exc:  # noqa: BLE001 - 识图失败按业务错误返回
        logger.warning("image analysis failed: %s", exc)
        return {"error": "image_analysis_failed"}

    description = (analysis.image_description or "").strip()
    if not description:
        return {"error": "image_description_empty"}

    # Stage 2：把描述作为上下文，正常生成回复
    payload: list[dict[str, str]] = []
    if system:
        payload.append({"role": "system", "content": system})
    payload.extend(messages)
    payload.append({"role": "user", "content": build_image_context(description, analysis.sub_intent)})

    try:
        result = await gateway.chat_messages(
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=payload,
            temperature=temperature,
            max_tokens=max_tokens,
            provider=provider,
            lane_name="interactive",
        )
    except ModelGatewayError as exc:
        logger.warning("image chat text stage failed: %s", exc)
        return {"error": "text_stage_failed"}

    content = (result.content or "").strip()
    if not content:
        return {"error": "empty_content"}
    return {"content": content, "imageDescription": description}


__all__ = ["build_image_context", "chat_with_image"]
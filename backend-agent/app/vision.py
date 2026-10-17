"""视觉分析域：两段式识图的 Stage 1（结构化 JSON）。

逐行为移植 Java VisionAnalysisParser / VISION_ANALYSIS_JSON_INSTRUCTION，
字段与措辞一字不改 —— 行为保真是本次迁移的第一约束（资产清单第 1 条）。
"""

import asyncio
import logging
from dataclasses import dataclass

from app.config import get_settings
from app.gateway import (
    GatewayResult,
    ImagePart,
    ModelGateway,
    ModelGatewayError,
    TextPart,
    extract_json_object,
)

logger = logging.getLogger(__name__)

# 与 Java VisionChatService.VISION_ANALYSIS_JSON_INSTRUCTION 逐字一致
VISION_ANALYSIS_JSON_INSTRUCTION = """
你是图片分析助手，只输出 JSON，不输出 markdown，不输出解释。
JSON 字段固定为：subIntent, confidence, imageDescription。
- subIntent: 判断用户发送这张图片的子意图，如求识图/分享日常/展示成果/吐槽/求建议/闲聊。
- confidence: 诚实表达看得清程度。看不清、模糊、遮挡、分辨率不足时必须明确写低置信。
- imageDescription: 只写客观可见内容，不脑补，不扮演角色。
输出格式示例：
{"subIntent":"求识图","confidence":"high","imageDescription":"一只橘猫趴在窗台上"}
"""


@dataclass(frozen=True)
class VisionAnalysisResult:
    sub_intent: str | None
    confidence: str | None
    image_description: str | None


def _text_or_none(value: object) -> str | None:
    return value if isinstance(value, str) and value.strip() else None


def parse_vision_analysis(raw: str) -> VisionAnalysisResult:
    """解析 VL 输出；非法 JSON 抛 ValueError（对齐 Java IllegalArgumentException→AI_PROVIDER_ERROR）。"""
    parsed = extract_json_object(raw)
    if parsed is None:
        raise ValueError("vision analysis payload must be a JSON object")
    return VisionAnalysisResult(
        sub_intent=_text_or_none(parsed.get("subIntent")),
        confidence=_text_or_none(parsed.get("confidence")),
        image_description=_text_or_none(parsed.get("imageDescription")),
    )


async def analyze_image(
    gateway: ModelGateway,
    *,
    image_png_base64: str,
    max_tokens: int,
) -> VisionAnalysisResult:
    """Stage 1：视觉 JSON 分析（temperature 0.1 / maxTokens 与 Java 一致）。"""
    settings = get_settings()
    try:
        result: GatewayResult = await gateway.chat(
            base_url=settings.multimodal_base_url,
            api_key=settings.multimodal_api_key,
            model=settings.multimodal_model,
            system=VISION_ANALYSIS_JSON_INSTRUCTION,
            parts=[ImagePart(png_base64=image_png_base64)],
            temperature=0.1,
            max_tokens=max_tokens,
        )
    except ModelGatewayError as exc:
        raise ValueError(f"vision analysis failed: {exc}") from exc

    if not result.content or not result.content.strip():
        raise ValueError("vision analysis returned empty")
    return parse_vision_analysis(result.content)


async def analyze_image_bounded(
    gateway: ModelGateway,
    *,
    image_png_base64: str,
    max_tokens: int,
) -> VisionAnalysisResult | None:
    """带并发护栏的 Stage 1；失败返回 None（调用方静默降级，对齐 Java 行为：返回 null）。"""
    settings = get_settings()
    if not settings.multimodal_enabled or not settings.multimodal_api_key:
        return None
    try:
        return await analyze_image(
            gateway, image_png_base64=image_png_base64, max_tokens=max_tokens
        )
    except ValueError as exc:
        logger.warning("Desktop observe: vision analysis failed: %s", exc)
        return None


def run_in_thread(coro: object) -> object:
    """占位：同步调用方经 FastAPI to_thread 进入时无需额外包装。"""
    return asyncio.get_event_loop()


__all__ = [
    "VISION_ANALYSIS_JSON_INSTRUCTION",
    "VisionAnalysisResult",
    "analyze_image",
    "analyze_image_bounded",
    "parse_vision_analysis",
    "run_in_thread",
    "TextPart",
]

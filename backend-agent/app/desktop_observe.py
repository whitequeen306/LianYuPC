"""桌面感知问候生成：两段式的 Stage 2（文本模型生成桌宠问候）。

移植 Java VisionChatService.observeDesktop + buildDesktopGreetingPrompt，
prompt 措辞逐字保留（资产第 1 条：行为保真）。
"""

import asyncio
import logging
from dataclasses import dataclass

from app.config import get_settings
from app.gateway import GatewayResult, ModelGateway, ModelGatewayError, TextPart
from app.vision import VisionAnalysisResult, analyze_image_bounded

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class DesktopObserveRequest:
    user_id: int
    image_base64: str
    window_title: str | None
    persona: str | None
    provider: str | None = None
    model: str | None = None


def build_desktop_greeting_prompt(
    persona: str | None, window_title: str | None, analysis: VisionAnalysisResult | None
) -> str:
    """逐字移植 Java buildDesktopGreetingPrompt（VisionChatService.java:536-548）。

    空值语义对齐 Java：
    - description 空值 → ""（Java: analysis.imageDescription() != null ? ... : ""）
    - confidence 空值 → "unknown"（Java: analysis.confidence() != null ? ... : "unknown"）
    """
    persona_text = persona if persona and persona.strip() else "你是一个可爱的桌面宠物。"
    win_title = window_title if window_title and window_title.strip() else "未知"
    description = (analysis.image_description or "") if analysis else ""
    confidence = (analysis.confidence or "unknown") if analysis else "unknown"
    return (
        f"{persona_text}\n\n"
        f"你正在看着用户的电脑屏幕。当前画面：{description}\n"
        f"图像可辨识度：{confidence}\n"
        f"用户正在使用的窗口：{win_title}\n\n"
        "如果图像可辨识度低，请自然承认看不太清。"
        "请用你的角色语气，对用户正在做的事情说一句话。\n"
        "要求：自然、口语化、不超过40字。不要加括号或动作描写。"
    )


async def observe_desktop(
    gateway: ModelGateway,
    text_client: object | None = None,
    *,
    request: DesktopObserveRequest,
) -> str | None:
    """两段式桌面感知。返回问候语或 None（各阶段失败均静默降级，对齐 Java 返回 null）。

    语义对齐 Java observeDesktop（VisionChatService.java:98-154）：
    - Stage 1 失败/空描述 → None
    - Stage 2 空回复 → None
    """
    settings = get_settings()

    # Stage 1
    analysis = await analyze_image_bounded(
        gateway,
        image_png_base64=request.image_base64,
        max_tokens=settings.multimodal_describe_max_tokens,
    )
    if analysis is None or not (analysis.image_description or "").strip():
        logger.warning("Desktop observe: vision analysis returned empty description")
        return None
    logger.info(
        "Desktop observe: vision result confidence=%s, subIntent=%s",
        analysis.confidence,
        analysis.sub_intent,
    )

    # Stage 2：文本模型生成问候。
    # Java 侧此处走用户 vault（resolveVault）——strangler 阶段 1 仍由 Java 侧承担 vault 解析，
    # 通过请求参数注入 base_url/api_key/model（由 Java 适配层传入），Python 侧不做密钥管理。
    stage2 = DesktopStage2Credentials.from_request(request)
    if stage2 is None:
        logger.warning("Desktop observe: stage-2 credentials absent, skip greeting")
        return None

    greeting_prompt = build_desktop_greeting_prompt(request.persona, request.window_title, analysis)
    try:
        result: GatewayResult = await gateway.chat(
            base_url=stage2.base_url,
            api_key=stage2.api_key,
            model=stage2.model,
            system=None,
            parts=[TextPart(text=greeting_prompt)],
            temperature=0.8,
            max_tokens=1024,
        )
    except ModelGatewayError as exc:
        logger.warning("Desktop observe: greeting generation failed: %s", exc)
        return None

    greeting = (result.content or "").strip()
    if not greeting:
        logger.warning("Desktop observe: greeting generation returned empty")
        return None
    logger.info("Desktop observe: greeting generated (%d chars)", len(greeting))
    return greeting


@dataclass(frozen=True)
class DesktopStage2Credentials:
    base_url: str
    api_key: str
    model: str

    @classmethod
    def from_request(cls, request: DesktopObserveRequest) -> "DesktopStage2Credentials | None":
        # 阶段 1 由 Java 适配层解析用户 vault 后传入；未传则放弃 Stage 2
        base_url = (request.provider or "").strip()
        api_key = (request.model or "").strip()
        if not base_url or not api_key:
            return None
        return cls(base_url=base_url, api_key=api_key, model="default")


def run_observe_sync(gateway: ModelGateway, request: DesktopObserveRequest) -> str | None:
    """同步包装，供 Java 侧经 HTTP 调用后（或测试）直接使用。"""
    return asyncio.run(observe_desktop(gateway, request=request))


__all__ = [
    "DesktopObserveRequest",
    "DesktopStage2Credentials",
    "build_desktop_greeting_prompt",
    "observe_desktop",
    "run_observe_sync",
]

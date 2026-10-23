"""视觉分析域：两段式识图的 Stage 1（结构化 JSON）。

逐行为移植 Java VisionAnalysisParser / VISION_ANALYSIS_JSON_INSTRUCTION，
字段与措辞一字不改 —— 行为保真是本次迁移的第一约束（资产清单第 1 条）。
"""

import asyncio
import logging
from dataclasses import dataclass

from app.config import get_settings
from app.resilience import GatewayError
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
    except GatewayError as exc:
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


# ─────────────────────────── 桌面能力层：屏幕理解（Stage 1 的兄弟版） ───────────────────────────
#
# 与上面的「识图聊天」不同：这里只为**操作**服务——说清界面上有什么、下一步该点哪个编号。
# 坐标系与元素由 Electron 提供（UIA 收割的矩形），模型只负责「选编号」，
# 不让它算像素：这既提高了准确率，也让坐标永远来自真实控件而不是模型幻觉。

SCREEN_ANALYSIS_INSTRUCTION = """你是电脑操作助手，只输出 JSON，不输出 markdown、不输出解释。
JSON 字段固定为：summary, app, confidence。
- summary: 一句话描述当前界面在做什么、用户处于哪个位置（30 字内，客观，不脑补）。
- app: 你判断的当前应用名（看不清就写 unknown）。
- confidence: high / medium / low。画面模糊、被遮挡、分辨率不足时必须写 low。
不要给出坐标，不要建议点击位置，点哪里由编号清单决定。"""


@dataclass(frozen=True)
class ScreenAnalysis:
    summary: str
    app: str
    confidence: str


def parse_screen_analysis(raw: str) -> ScreenAnalysis:
    """解析屏幕理解 JSON；坏输出不抛错（操作链路不能因一句话没解析出来就断）。"""
    parsed = extract_json_object(raw)
    if parsed is None:
        text = (raw or "").strip().replace("\n", " ")
        return ScreenAnalysis(summary=text[:120], app="unknown", confidence="low")
    summary = _text_or_none(parsed.get("summary")) or ""
    return ScreenAnalysis(
        summary=summary[:200],
        app=_text_or_none(parsed.get("app")) or "unknown",
        confidence=_text_or_none(parsed.get("confidence")) or "low",
    )


def _element_line(element: dict[str, object]) -> str:
    role = str(element.get("role") or "").strip()
    label = str(element.get("label") or "").strip() or "（无文字）"
    parts = [f"[{element.get('id')}] {label}"]
    if role:
        parts.append(f"({role})")
    if element.get("x") is not None and element.get("y") is not None:
        parts.append(f"@{element.get('x')},{element.get('y')}")
    return " ".join(parts)


def format_screen_report(
    analysis: ScreenAnalysis | None,
    elements: list[dict[str, object]] | None,
    *,
    window_title: str | None = None,
    app_process: str | None = None,
    max_elements: int = 40,
) -> str:
    """把「视觉摘要 + 元素编号清单」拼成一段给模型的观察结果。

    元素清单是**下一步操作的唯一依据**：模型只能引用这里出现过的编号，
    编号到矩形的映射留在 Electron（它随时可以重新收割，坐标不会过期）。
    """
    lines: list[str] = []
    if window_title or app_process:
        lines.append(f"当前前台：{window_title or '未知标题'}（{app_process or '未知进程'}）")
    if analysis is not None and analysis.summary:
        conf = f"，可辨识度 {analysis.confidence}" if analysis.confidence else ""
        lines.append(f"界面内容：{analysis.summary}{conf}")
    items = [e for e in (elements or []) if isinstance(e, dict) and e.get("id") is not None]
    if items:
        lines.append(f"可操作元素（共 {len(items)} 个，点击时用编号）：")
        lines.extend("  " + _element_line(e) for e in items[:max_elements])
        if len(items) > max_elements:
            lines.append(f"  ……还有 {len(items) - max_elements} 个未列出，可缩小范围再看。")
    else:
        lines.append("没有拿到可操作元素清单：只能描述界面，不要凭空点击。")
    return "\n".join(lines)


__all__ = [
    "SCREEN_ANALYSIS_INSTRUCTION",
    "VISION_ANALYSIS_JSON_INSTRUCTION",
    "ScreenAnalysis",
    "VisionAnalysisResult",
    "analyze_image",
    "analyze_image_bounded",
    "format_screen_report",
    "parse_screen_analysis",
    "parse_vision_analysis",
    "run_in_thread",
    "TextPart",
]

"""把「会动的图」拆成几张静帧，再交给视觉模型。

为什么需要这一层：VLM 吃 GIF 时只看得到第一帧（实测对一张「红点从左跑到右」的 8 帧
动图，回答「一个静止的红点，没有在动」）。QQ 的动图表情包传过来就是 image/gif，直接送
模型等于只看到开头一个定格。

试过但**没用**的两条路：
1. 拼成 3x2 帧条 —— 模型把它当成「两行三列的网格」静态图案；
2. 帧条 + 「这是连续瞬间，请描述动作」的说明（哪怕同时改系统提示开口子）—— 仍然只描述网格，
   因为识图系统提示要求「只写客观可见内容，不脑补」。
3. 帧条 + 首末帧差异区域的坐标 —— 依然被读成网格。

**有效**的做法：把 6 帧作为 6 张独立的图按顺序一起送，模型直接答「红色圆形从左向右
水平移动，第 1 帧在左、第 6 帧到右侧」。所以这里拆帧、不再拼条。

**怎么判断「会动」**：不看 content_type、不看魔数，直接问图片自己有几帧（Pillow 的
`n_frames`）。GIF / APNG / 动画 WebP 它都答得出来，静态图回 1。原先按魔数只认 image/gif，
遇到 APNG、动画 WebP 就会当成静态图、又退回「只看到第一帧」。
`n_frames`）。GIF / APNG / 动画 WebP 它都答得出来，静态图回 1。原先按魔数只认 image/gif，
遇到 APNG、动画 WebP 就会当成静态图、又退回「只看到第一帧」。

其它格式（静态图文）原样透传，不重建、不加说明，省 token。
"""

from __future__ import annotations

import base64
import binascii
import io
import logging
from dataclasses import dataclass

logger = logging.getLogger(__name__)

MAX_FRAMES = 6
MAX_FRAME_SIDE = 360
_ANIMATION_HINT = (
    "这是同一段动图/表情包按时间顺序抽取的 {n} 张静帧（第 1 张是最早的）。"
    "请把它们当成同一个场景的连续瞬间，说出发生了什么动作、什么东西在动、朝哪个方向动。"
)


@dataclass(frozen=True)
class VisionImage:
    """送去给视觉模型的一帧：base64 + mime。"""

    base64: str
    mime: str = "image/png"


@dataclass(frozen=True)
class VisionFrames:
    """一次识图要送的全部图：单图就是长度 1；动图是拆开的 N 帧。"""

    images: tuple[VisionImage, ...]
    hint: str | None = None


def _decode(raw_base64: str) -> bytes | None:
    try:
        return base64.b64decode(raw_base64, validate=False)
    except (binascii.Error, ValueError):
        return None


def sniff_mime(data: bytes) -> str:
    if data.startswith(b"GIF87a") or data.startswith(b"GIF89a"):
        return "image/gif"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return "image/png"


def split_frames(
    data: bytes, *, max_frames: int = MAX_FRAMES, max_side: int = MAX_FRAME_SIDE
) -> list[bytes] | None:
    """多帧图（GIF / APNG / 动画 WebP）→ N 张独立 PNG。单帧或解码失败返回 None（调用方原样透传）。"""
    try:
        from PIL import Image  # 延迟导入：没有 Pillow 时整条链路仍能跑静态图
    except ImportError:  # pragma: no cover - 依赖缺失时降级
        logger.warning("media_frames: Pillow missing, animated image stays single-frame")
        return None

    try:
        img = Image.open(io.BytesIO(data))
        total = int(getattr(img, "n_frames", 1))
        if total <= 1:
            return None
        count = min(max_frames, total)
        picks = [round(i * (total - 1) / max(1, count - 1)) for i in range(count)]
        out: list[bytes] = []
        for idx in dict.fromkeys(picks):
            img.seek(idx)
            frame = img.convert("RGB").copy()
            frame.thumbnail((max_side, max_side))
            buf = io.BytesIO()
            frame.save(buf, format="PNG", optimize=True)
            out.append(buf.getvalue())
        return out if len(out) > 1 else None
    except Exception as exc:  # noqa: BLE001 - 任何解码问题都退回透传
        logger.warning("media_frames: split frames failed: %s", exc)
        return None


def prepare_vision_frames(raw_base64: str) -> VisionFrames:
    """给视觉模型准备图：多帧图拆成 N 张；其余原样透传。永不抛异常。"""
    data = _decode(raw_base64)
    if not data:
        return VisionFrames(images=(VisionImage(base64=raw_base64),))
    mime = sniff_mime(data)
    # 不信 mime、也不信 content_type：直接问图片有几帧（GIF / APNG / 动画 WebP 都覆盖）
    frames = split_frames(data)
    if frames:
        return VisionFrames(
            images=tuple(
                VisionImage(base64=base64.b64encode(png).decode()) for png in frames
            ),
            hint=_ANIMATION_HINT.format(n=len(frames)),
        )
    return VisionFrames(images=(VisionImage(base64=raw_base64, mime=mime),))


__all__ = [
    "VisionFrames",
    "VisionImage",
    "prepare_vision_frames",
    "sniff_mime",
    "split_frames",
]

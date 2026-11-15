"""把「会动的图」摊成一张帧条，再交给视觉模型。

为什么需要这一层：VLM 吃 GIF 时只看得到第一帧（实测 deepseek-v4.1-flash 对一张
「红点从左跑到右」的 8 帧动图，回答「一个静止的红点，没有在动」；把 6 帧拼成 3x2
的帧条后，回答「红点在向右移动」）。QQ 的动图表情包传过来就是 image/gif，直接送模型
等于只看到开头一个定格。

策略：只在真的是多帧 GIF 时才重建（帧条），其它格式原样透传——静态图不动，省 token。
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
COLUMNS = 3
_ANIMATION_HINT = (
    "这张图是一段动图/表情包按时间顺序抽取的 {n} 帧，"
    f"从左到右、从上到下排列（每行 {COLUMNS} 帧）。"
    "请把它们当成同一个场景的连续瞬间，在 imageDescription 里写出发生了什么动作、"
    "什么东西在动、朝哪个方向动（例如「一个小球从左向右移动」）；"
    "不要把它描述成静止的格子或阵列图案——格子和阵列只是动图被摊开的样子。"
)


@dataclass(frozen=True)
class VisionImage:
    """送去给视觉模型的一张图：base64 + mime + 可选的说明文字。"""

    base64: str
    mime: str = "image/png"
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


def tile_frames(data: bytes, *, max_frames: int = MAX_FRAMES, max_side: int = MAX_FRAME_SIDE) -> tuple[bytes, int] | None:
    """多帧 GIF → 单张帧条 PNG。单帧或解码失败返回 None（调用方原样透传）。"""
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
        frames = []
        for idx in dict.fromkeys(picks):
            img.seek(idx)
            frame = img.convert("RGB").copy()
            frame.thumbnail((max_side, max_side))
            frames.append(frame)
        if len(frames) <= 1:
            return None
        columns = COLUMNS if len(frames) > 2 else len(frames)
        rows = (len(frames) + columns - 1) // columns
        cell_w = max(f.width for f in frames)
        cell_h = max(f.height for f in frames)
        sheet = Image.new("RGB", (cell_w * columns, cell_h * rows), (255, 255, 255))
        for i, frame in enumerate(frames):
            sheet.paste(frame, ((i % columns) * cell_w, (i // columns) * cell_h))
        out = io.BytesIO()
        sheet.save(out, format="PNG", optimize=True)
        return out.getvalue(), len(frames)
    except Exception as exc:  # noqa: BLE001 - 任何解码问题都退回透传
        logger.warning("media_frames: tile failed: %s", exc)
        return None


def prepare_vision_image(raw_base64: str) -> VisionImage:
    """给视觉模型准备图：多帧 GIF 变帧条；其余原样透传。永不抛异常。"""
    data = _decode(raw_base64)
    if not data:
        return VisionImage(base64=raw_base64)
    mime = sniff_mime(data)
    if mime != "image/gif":
        return VisionImage(base64=raw_base64, mime=mime)
    tiled = tile_frames(data)
    if tiled is None:
        return VisionImage(base64=raw_base64, mime=mime)
    png, frames = tiled
    return VisionImage(
        base64=base64.b64encode(png).decode(),
        mime="image/png",
        hint=_ANIMATION_HINT.format(n=frames),
    )


__all__ = ["VisionImage", "prepare_vision_image", "sniff_mime", "tile_frames"]

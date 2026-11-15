"""动图拆帧：GIF 只被看到第一帧时，模型会说「静止的圆点」。"""
import base64
import io

import pytest
from PIL import Image, ImageDraw

from app.media_frames import prepare_vision_frames, sniff_mime, split_frames


def _gif(frames: int = 8, size: tuple[int, int] = (120, 60)) -> bytes:
    imgs = []
    for i in range(frames):
        img = Image.new("RGB", size, (255, 255, 255))
        d = ImageDraw.Draw(img)
        x = 10 + i * 10
        d.ellipse((x, 20, x + 20, 40), fill=(200, 30, 30))
        imgs.append(img)
    buf = io.BytesIO()
    imgs[0].save(buf, format="GIF", save_all=True, append_images=imgs[1:], duration=100, loop=0)
    return buf.getvalue()


def _jpeg() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (40, 30), (10, 120, 200)).save(buf, format="JPEG")
    return buf.getvalue()


def test_sniff_mime_recognises_gif_png_jpeg():
    assert sniff_mime(_gif()) == "image/gif"
    assert sniff_mime(_jpeg()) == "image/jpeg"
    buf = io.BytesIO()
    Image.new("RGB", (8, 8)).save(buf, format="PNG")
    assert sniff_mime(buf.getvalue()) == "image/png"


def test_split_frames_gives_one_png_per_frame():
    frames = split_frames(_gif(8))
    assert frames is not None
    assert len(frames) == 8
    for png in frames:
        img = Image.open(io.BytesIO(png))
        assert img.format == "PNG"


def test_small_sticker_can_use_twelve_frames():
    """表情包常见几十帧、动作在后段，小尺寸放宽到 12 帧。"""
    frames = split_frames(_gif(20, (120, 60)))
    assert frames is not None
    assert len(frames) == 12


def test_large_animation_stays_at_six_frames():
    """照片尺寸的动图仍只抽 6 帧，避免 token 爆炸。"""
    frames = split_frames(_gif(20, (400, 400)))
    assert frames is not None
    assert len(frames) == 6


def test_split_frames_skips_single_frame_gif():
    buf = io.BytesIO()
    Image.new("RGB", (32, 32), (9, 9, 9)).save(buf, format="GIF")
    assert split_frames(buf.getvalue()) is None


def test_prepare_animated_gif_splits_and_hints():
    prepared = prepare_vision_frames(base64.b64encode(_gif(8)).decode())
    assert len(prepared.images) == 8
    assert all(img.mime == "image/png" for img in prepared.images)
    assert prepared.hint and "8 张" in prepared.hint
    Image.open(io.BytesIO(base64.b64decode(prepared.images[0].base64)))


def test_prepare_static_jpeg_passes_through_untouched():
    raw = _jpeg()
    prepared = prepare_vision_frames(base64.b64encode(raw).decode())
    assert len(prepared.images) == 1
    assert prepared.images[0].mime == "image/jpeg"
    assert prepared.hint is None
    assert base64.b64decode(prepared.images[0].base64) == raw


@pytest.mark.parametrize("bad", ["", "not-base64!!", base64.b64encode(b"hello").decode()])
def test_prepare_never_raises_on_garbage(bad):
    prepared = prepare_vision_frames(bad)
    assert len(prepared.images) == 1
    assert prepared.hint is None


def _animated(fmt: str, frames: int = 5) -> bytes:
    imgs = []
    for i in range(frames):
        img = Image.new("RGB", (80, 40), (255, 255, 255))
        d = ImageDraw.Draw(img)
        x = 5 + i * 12
        d.ellipse((x, 10, x + 15, 25), fill=(20, 90, 200))
        imgs.append(img)
    buf = io.BytesIO()
    imgs[0].save(
        buf,
        format=fmt,
        save_all=True,
        append_images=imgs[1:],
        duration=100,
        loop=0,
    )
    return buf.getvalue()


@pytest.mark.parametrize("fmt,mime", [("PNG", "image/png"), ("WEBP", "image/webp")])
def test_prepare_splits_apng_and_animated_webp(fmt, mime):
    """不信 mime、问帧数：APNG 与动画 WebP 也必须被拆开，否则又会「只看到第一帧」。"""
    raw = _animated(fmt)
    assert sniff_mime(raw) == mime
    if getattr(Image.open(io.BytesIO(raw)), "n_frames", 1) <= 1:
        pytest.skip(f"Pillow 的 {fmt} 编码器不支持动画")

    prepared = prepare_vision_frames(base64.b64encode(raw).decode())
    assert len(prepared.images) == 5
    assert all(img.mime == "image/png" for img in prepared.images)
    assert prepared.hint and "5 张" in prepared.hint


def test_prepare_static_png_stays_single():
    buf = io.BytesIO()
    Image.new("RGB", (16, 16), (1, 2, 3)).save(buf, format="PNG")
    prepared = prepare_vision_frames(base64.b64encode(buf.getvalue()).decode())
    assert len(prepared.images) == 1
    assert prepared.images[0].mime == "image/png"
    assert prepared.hint is None

"""动图帧条：GIF 只被看到第一帧时，模型会说「静止的圆点」。"""
import base64
import io

import pytest
from PIL import Image, ImageDraw

from app.media_frames import prepare_vision_image, sniff_mime, tile_frames


def _gif(frames: int = 8) -> bytes:
    imgs = []
    for i in range(frames):
        img = Image.new("RGB", (120, 60), (255, 255, 255))
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


def test_tile_frames_returns_six_frame_sheet():
    out = tile_frames(_gif())
    assert out is not None
    png, frames = out
    assert frames == 6
    sheet = Image.open(io.BytesIO(png))
    assert sheet.format == "PNG"
    # 3 列 2 行
    assert sheet.width % 3 == 0
    assert sheet.height % 2 == 0


def test_tile_frames_skips_single_frame_gif():
    buf = io.BytesIO()
    Image.new("RGB", (32, 32), (9, 9, 9)).save(buf, format="GIF")
    assert tile_frames(buf.getvalue()) is None


def test_prepare_animated_gif_becomes_png_with_hint():
    prepared = prepare_vision_image(base64.b64encode(_gif()).decode())
    assert prepared.mime == "image/png"
    assert prepared.hint and "6 帧" in prepared.hint
    # 帧条真的能解码
    Image.open(io.BytesIO(base64.b64decode(prepared.base64)))


def test_prepare_static_jpeg_passes_through():
    raw = _jpeg()
    prepared = prepare_vision_image(base64.b64encode(raw).decode())
    assert prepared.mime == "image/jpeg"
    assert prepared.hint is None
    assert base64.b64decode(prepared.base64) == raw


@pytest.mark.parametrize("bad", ["", "not-base64!!", base64.b64encode(b"hello").decode()])
def test_prepare_never_raises_on_garbage(bad):
    prepared = prepare_vision_image(bad)
    assert prepared.base64 == bad

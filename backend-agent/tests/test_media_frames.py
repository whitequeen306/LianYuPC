"""动图拆帧：GIF 只被看到第一帧时，模型会说「静止的圆点」。"""
import base64
import io

import pytest
from PIL import Image, ImageDraw

from app.media_frames import prepare_vision_frames, sniff_mime, split_frames


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


def test_split_frames_gives_six_pngs():
    frames = split_frames(_gif())
    assert frames is not None
    assert len(frames) == 6
    for png in frames:
        img = Image.open(io.BytesIO(png))
        assert img.format == "PNG"


def test_split_frames_skips_single_frame_gif():
    buf = io.BytesIO()
    Image.new("RGB", (32, 32), (9, 9, 9)).save(buf, format="GIF")
    assert split_frames(buf.getvalue()) is None


def test_prepare_animated_gif_returns_six_images_with_hint():
    prepared = prepare_vision_frames(base64.b64encode(_gif()).decode())
    assert len(prepared.images) == 6
    assert all(img.mime == "image/png" for img in prepared.images)
    assert prepared.hint and "6 张" in prepared.hint
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

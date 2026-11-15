"""识图调用要把「思考模式」显式关掉：推理型视觉模型会让一张小图慢十倍。"""
from types import SimpleNamespace

import pytest

from app import vision as vision_mod


def _settings(thinking: str) -> SimpleNamespace:
    return SimpleNamespace(
        multimodal_base_url="https://example.invalid/v1",
        multimodal_api_key="k",
        multimodal_model="deepseek-v4.1-flash",
        multimodal_thinking=thinking,
    )


@pytest.mark.parametrize(
    "configured,expected",
    [
        ("off", {"enable_thinking": False}),
        ("false", {"enable_thinking": False}),
        ("on", {"enable_thinking": True}),
        ("", {}),
        ("  ", {}),
        ("whatever", {}),
    ],
)
def test_thinking_flag_mapping(monkeypatch, configured, expected):
    monkeypatch.setattr(vision_mod, "get_settings", lambda: _settings(configured))
    assert vision_mod.vision_thinking_extra() == expected


@pytest.mark.asyncio
async def test_analyze_image_forwards_thinking_flag(monkeypatch):
    monkeypatch.setattr(vision_mod, "get_settings", lambda: _settings("off"))

    captured = {}

    class _Gateway:
        async def chat(self, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(
                content='{"subIntent":"求识图","confidence":"high","imageDescription":"一只橘猫"}'
            )

    result = await vision_mod.analyze_image(
        _Gateway(), image_png_base64="AAA", max_tokens=128
    )

    assert captured["extra"] == {"enable_thinking": False}
    assert captured["model"] == "deepseek-v4.1-flash"
    assert result.image_description == "一只橘猫"


@pytest.mark.asyncio
async def test_analyze_image_splits_animated_gif_into_frames(monkeypatch):
    """动图要拆成多张独立图，并在前面附一句「这是同一段动图的 N 张静帧」。"""
    import base64
    import io

    from PIL import Image, ImageDraw

    monkeypatch.setattr(vision_mod, "get_settings", lambda: _settings("off"))

    imgs = []
    for i in range(8):
        img = Image.new("RGB", (60, 40), (255, 255, 255))
        ImageDraw.Draw(img).ellipse((5 + i * 5, 10, 20 + i * 5, 25), fill=(200, 30, 30))
        imgs.append(img)
    buf = io.BytesIO()
    imgs[0].save(buf, format="GIF", save_all=True, append_images=imgs[1:], duration=100, loop=0)
    gif_b64 = base64.b64encode(buf.getvalue()).decode()

    captured = {}

    class _Gateway:
        async def chat(self, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(
                content='{"subIntent":"求识图","confidence":"high","imageDescription":"红点在向右移动"}'
            )

    await vision_mod.analyze_image(_Gateway(), image_png_base64=gif_b64, max_tokens=128)

    parts = captured["parts"]
    assert len(parts) == 7  # 1 句说明 + 6 帧
    assert "6 张" in parts[0].text
    assert all(getattr(p, "mime", None) == "image/png" for p in parts[1:])

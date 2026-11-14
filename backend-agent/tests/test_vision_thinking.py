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

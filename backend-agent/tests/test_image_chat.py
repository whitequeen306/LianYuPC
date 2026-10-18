"""图片消息回合测试（两段式）。

守的边界：
- 识图失败 → error，不进入文本阶段（避免没有图片信息还硬回）
- 描述为空 → error（否则模型会收到空上下文）
- 成功 → 返回 content + imageDescription，且描述被注入文本阶段
"""

import base64

import httpx
import pytest
import respx

from app.gateway import ModelGateway
from app.http_client import outbound_timeout
from app.image_chat import build_image_context, chat_with_image
from app.resilience import reset_breakers, reset_lanes

VISION_BASE = "https://vision.test/v1"
TEXT_BASE = "https://text.test/v1"

# 1x1 PNG
PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


@pytest.fixture(autouse=True)
def _reset():
    reset_breakers()
    reset_lanes()
    yield
    reset_breakers()
    reset_lanes()


def _client():
    return httpx.AsyncClient(timeout=outbound_timeout())


async def _run(**overrides):
    kwargs = dict(
        image_png_base64=PNG_B64,
        vision_base_url=VISION_BASE, vision_api_key="vk", vision_model="vl",
        base_url=TEXT_BASE, api_key="tk", model="tm",
        system="你是角色", messages=[{"role": "user", "content": "看看这个"}],
        temperature=0.8, max_tokens=200,
    )
    kwargs.update(overrides)
    client = _client()
    try:
        return await chat_with_image(ModelGateway(client=client), **kwargs)
    finally:
        await client.aclose()


def _vision_ok(description="一只橘猫趴在窗台", intent="分享日常", confidence="high"):
    import json
    return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(
        {"subIntent": intent, "confidence": confidence, "imageDescription": description})}}]})


@pytest.mark.asyncio
@respx.mock
async def test_two_stage_success():
    respx.post(f"{VISION_BASE}/chat/completions").mock(return_value=_vision_ok())
    respx.post(f"{TEXT_BASE}/chat/completions").respond(
        json={"choices": [{"message": {"content": "这猫好可爱"}}]}
    )
    result = await _run()
    assert result["content"] == "这猫好可爱"
    assert result["imageDescription"] == "一只橘猫趴在窗台"


@pytest.mark.asyncio
@respx.mock
async def test_description_is_injected_into_text_stage():
    respx.post(f"{VISION_BASE}/chat/completions").mock(return_value=_vision_ok("桌上有一杯咖啡"))
    text_route = respx.post(f"{TEXT_BASE}/chat/completions").respond(
        json={"choices": [{"message": {"content": "在喝咖啡呀"}}]}
    )
    await _run()

    body = text_route.calls[0].request.content.decode()
    assert "桌上有一杯咖啡" in body, "识图结果必须注入文本阶段，否则模型看不到图"
    assert "分享日常" in body


@pytest.mark.asyncio
@respx.mock
async def test_vision_failure_does_not_call_text_stage():
    respx.post(f"{VISION_BASE}/chat/completions").respond(status_code=500)
    text_route = respx.post(f"{TEXT_BASE}/chat/completions").respond(
        json={"choices": [{"message": {"content": "不该被调用"}}]}
    )
    result = await _run()
    assert result == {"error": "image_analysis_failed"}
    assert text_route.call_count == 0


@pytest.mark.asyncio
@respx.mock
async def test_empty_description_returns_error():
    respx.post(f"{VISION_BASE}/chat/completions").mock(return_value=_vision_ok(description="  "))
    result = await _run()
    assert result == {"error": "image_description_empty"}


@pytest.mark.asyncio
async def test_missing_image_returns_error():
    assert await _run(image_png_base64="") == {"error": "missing_image"}


@pytest.mark.asyncio
@respx.mock
async def test_text_stage_failure_returns_error():
    respx.post(f"{VISION_BASE}/chat/completions").mock(return_value=_vision_ok())
    respx.post(f"{TEXT_BASE}/chat/completions").respond(status_code=503)
    result = await _run()
    assert result == {"error": "text_stage_failed"}


def test_context_template_contains_required_parts():
    ctx = build_image_context("一只猫", "求摸")
    assert "一只猫" in ctx
    assert "求摸" in ctx
    assert "不要复述" in ctx


def test_context_handles_missing_intent():
    assert "未明确" in build_image_context("一只猫", None)


def test_png_fixture_is_decodable():
    assert base64.b64decode(PNG_B64)[:4] == b"\x89PNG"

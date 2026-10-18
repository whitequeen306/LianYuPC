"""通用文本回合端点测试。"""

import httpx
import pytest
import respx

from app.gateway import ModelGateway
from app.http_client import outbound_timeout
from app.resilience import reset_breakers, reset_lanes
from app.text_turn import complete_text

BASE = "https://fake/v1"


@pytest.fixture(autouse=True)
def _reset():
    reset_breakers()
    reset_lanes()
    yield
    reset_breakers()
    reset_lanes()


def _client():
    return httpx.AsyncClient(timeout=outbound_timeout())


async def _call(**kwargs):
    defaults = dict(
        base_url=BASE, api_key="k", model="m",
        system="你是角色", user_text="写今天的日记",
        temperature=0.85, max_tokens=500,
    )
    defaults.update(kwargs)
    client = _client()
    try:
        return await complete_text(ModelGateway(client=client), **defaults)
    finally:
        await client.aclose()


@pytest.mark.asyncio
@respx.mock
async def test_returns_content():
    route = respx.post(f"{BASE}/chat/completions").respond(
        json={"choices": [{"message": {"content": '{"title":"雨天","content":"..."}'}}]}
    )
    result = await _call()
    assert result["content"].startswith('{"title"')
    # system 与 user 都要发出去
    body = route.calls[0].request.content.decode()
    assert "你是角色" in body and "写今天的日记" in body


@pytest.mark.asyncio
@respx.mock
async def test_empty_content_reports_error():
    respx.post(f"{BASE}/chat/completions").respond(
        json={"choices": [{"message": {"content": "   "}}]}
    )
    assert await _call() == {"error": "empty_content"}


@pytest.mark.asyncio
@respx.mock
async def test_http_error_reports_error_not_raise():
    respx.post(f"{BASE}/chat/completions").respond(status_code=503)
    result = await _call()
    assert "error" in result


@pytest.mark.asyncio
@respx.mock
async def test_system_optional():
    route = respx.post(f"{BASE}/chat/completions").respond(
        json={"choices": [{"message": {"content": "ok"}}]}
    )
    await _call(system="")
    body = route.calls[0].request.content.decode()
    assert '"role": "system"' not in body.replace('"role":"system"', '"role": "system"')

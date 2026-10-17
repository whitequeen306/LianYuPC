"""gateway 与 http 客户端红线测试。

- 出站 client 必须显式超时（红线：零超时禁用）
- ModelGateway 对非 200 / 畸形响应 / 网络异常统一抛 ModelGatewayError
"""

import httpx
import pytest
import respx

from app.gateway import ModelGateway, ModelGatewayError, decode_png_base64, extract_json_object
from app.http_client import outbound_timeout


def test_outbound_timeout_is_explicit():
    timeout = outbound_timeout()
    assert timeout.connect == 15.0
    assert timeout.read == 60.0
    assert timeout.write == 30.0
    assert timeout.connect > 0 and timeout.read > 0  # 红线：无零超时


def test_decode_png_base64_valid_and_invalid():
    import base64

    raw = base64.b64encode(b"\x89PNG fake").decode()
    assert decode_png_base64(raw) == b"\x89PNG fake"
    with pytest.raises(ValueError):
        decode_png_base64("!!!not-base64!!!")


def test_extract_json_object_three_forms():
    assert extract_json_object('<json>{"a":1}</json>') == {"a": 1}
    assert extract_json_object('```json\n{"a":1}\n```') == {"a": 1}
    assert extract_json_object('{"a":1}') == {"a": 1}
    assert extract_json_object("") is None
    assert extract_json_object("no json") is None


@pytest.mark.asyncio
@respx.mock
async def test_gateway_success():
    route = respx.post("https://fake/v1/chat/completions").respond(
        json={"choices": [{"message": {"content": "你好呀"}}], "usage": {"prompt_tokens": 3}}
    )
    # 注入被 respx 拦截的 client，避免 gateway 自建真实 transport
    mocked_client = httpx.AsyncClient(base_url="https://fake/v1", timeout=outbound_timeout())
    try:
        gateway = ModelGateway(client=mocked_client)
        result = await gateway.chat(
            base_url="https://fake/v1",
            api_key="k",
            model="m",
            system="sys",
            parts=[],
            temperature=0.1,
            max_tokens=10,
        )
    finally:
        await mocked_client.aclose()
    assert route.called
    assert result.content == "你好呀"
    assert result.prompt_tokens == 3


@pytest.mark.asyncio
@respx.mock
async def test_gateway_http_error_raises():
    respx.post("https://fake/v1/chat/completions").respond(status_code=502)
    mocked_client = httpx.AsyncClient(base_url="https://fake/v1", timeout=outbound_timeout())
    try:
        gateway = ModelGateway(client=mocked_client)
        with pytest.raises(ModelGatewayError):
            await gateway.chat(
                base_url="https://fake/v1", api_key="k", model="m",
                system=None, parts=[], temperature=0.1, max_tokens=10,
            )
    finally:
        await mocked_client.aclose()


@pytest.mark.asyncio
@respx.mock
async def test_gateway_malformed_response_raises():
    respx.post("https://fake/v1/chat/completions").respond(json={"unexpected": True})
    mocked_client = httpx.AsyncClient(base_url="https://fake/v1", timeout=outbound_timeout())
    try:
        gateway = ModelGateway(client=mocked_client)
        with pytest.raises(ModelGatewayError):
            await gateway.chat(
                base_url="https://fake/v1", api_key="k", model="m",
                system=None, parts=[], temperature=0.1, max_tokens=10,
            )
    finally:
        await mocked_client.aclose()


def test_httpx_client_uses_explicit_timeout():
    client = httpx.AsyncClient(timeout=outbound_timeout())
    assert client.timeout.connect == 15.0

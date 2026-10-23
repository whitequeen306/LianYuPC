"""内网执行通道：fail-closed、超时、错误码映射、签名。

通道错了整条电脑操作用不了，但它**不能**影响聊天主链路 ——
所以「不抛异常、返回 error_code」是这里最重要的断言。
"""

from __future__ import annotations

import httpx
import pytest
import respx

from app.capability.desktop_channel import (
    ERR_CHANNEL_NOT_CONFIGURED,
    ERR_CHANNEL_TIMEOUT,
    ERR_CHANNEL_UNREACHABLE,
    EXEC_PATH,
    DesktopChannel,
    sign_body,
)

BASE = "http://backend:8080"
URL = BASE + EXEC_PATH


def make_channel(token: str = "s3cret") -> DesktopChannel:
    return DesktopChannel(base_url=BASE, token=token)


def test_fail_closed_without_token():
    channel = DesktopChannel(base_url=BASE, token="")
    assert channel.configured is False


def test_signature_is_deterministic_and_body_sensitive():
    a = sign_body("k", "1700000000", b'{"x":1}')
    assert a == sign_body("k", "1700000000", b'{"x":1}')
    assert a != sign_body("k", "1700000001", b'{"x":1}')
    assert a != sign_body("k", "1700000000", b'{"x":2}')
    assert a.startswith("sha256=") and len(a) == len("sha256=") + 64


@pytest.mark.asyncio
async def test_exec_without_token_is_refused(monkeypatch):
    # 构造一个没有密钥的通道；即便调用也不应发请求
    channel = DesktopChannel(base_url=BASE, token="")
    with respx.mock(assert_all_called=False) as mock:
        route = mock.post(URL).mock(return_value=httpx.Response(200, json={"ok": True}))
        result = await channel.exec(
            user_id=1, session_id="s", step_id="s#1", tool="desk_window_list",
            arguments={}, timeout_ms=3000, evidence="user_request",
        )
        assert result.ok is False
        assert result.error_code == ERR_CHANNEL_NOT_CONFIGURED
        assert route.call_count == 0


@pytest.mark.asyncio
async def test_exec_maps_success_and_keeps_images_out_of_text():
    channel = make_channel()
    payload = {
        "ok": True,
        "data": {"elements": [{"id": 3, "label": "播放"}], "windowTitle": "网易云"},
        "images": [{"mime": "image/jpeg", "b64": "AAAA", "w": 1024, "h": 576}],
        "elapsedMs": 42,
    }
    with respx.mock:
        respx.post(URL).mock(return_value=httpx.Response(200, json=payload))
        result = await channel.exec(
            user_id=1, session_id="s", step_id="s#1", tool="desk_screen_read",
            arguments={"evidence": "user_request"}, timeout_ms=9000,
            evidence="user_request",
        )
    assert result.ok is True
    assert result.has_image is True
    assert result.elapsed_ms == 42
    assert "AAAA" not in result.text, "图片绝不能进文本上下文"


@pytest.mark.asyncio
async def test_exec_maps_error_code():
    channel = make_channel()
    with respx.mock:
        respx.post(URL).mock(return_value=httpx.Response(
            200, json={"ok": False, "errorCode": "window_not_found"}))
        result = await channel.exec(
            user_id=1, session_id="s", step_id="s#1", tool="desk_window_focus",
            arguments={}, timeout_ms=5000, evidence="user_request",
        )
    assert result.ok is False
    assert result.error_code == "window_not_found"


@pytest.mark.asyncio
async def test_exec_timeout_does_not_raise():
    channel = make_channel()
    with respx.mock:
        respx.post(URL).mock(side_effect=httpx.ReadTimeout("slow"))
        result = await channel.exec(
            user_id=1, session_id="s", step_id="s#1", tool="desk_click",
            arguments={}, timeout_ms=3000, evidence="user_request",
        )
    assert result.ok is False
    assert result.error_code == ERR_CHANNEL_TIMEOUT


@pytest.mark.asyncio
async def test_exec_transport_error_does_not_raise():
    channel = make_channel()
    with respx.mock:
        respx.post(URL).mock(side_effect=httpx.ConnectError("nope"))
        result = await channel.exec(
            user_id=1, session_id="s", step_id="s#1", tool="desk_click",
            arguments={}, timeout_ms=3000, evidence="user_request",
        )
    assert result.ok is False
    assert result.error_code == ERR_CHANNEL_UNREACHABLE


@pytest.mark.asyncio
async def test_exec_5xx_maps_to_unreachable():
    channel = make_channel()
    with respx.mock:
        respx.post(URL).mock(return_value=httpx.Response(502, text="bad gateway"))
        result = await channel.exec(
            user_id=1, session_id="s", step_id="s#1", tool="desk_click",
            arguments={}, timeout_ms=3000, evidence="user_request",
        )
    assert result.ok is False
    assert result.error_code == ERR_CHANNEL_UNREACHABLE


@pytest.mark.asyncio
async def test_progress_failure_is_swallowed():
    channel = make_channel()
    with respx.mock:
        respx.post(BASE + "/internal/agent/desktop/progress").mock(
            side_effect=httpx.ConnectError("nope"))
        await channel.progress(user_id=1, session_id="s", step_id="s#1",
                               code="desk_click", text="我点一下")


@pytest.mark.asyncio
async def test_headers_carry_signature_and_user():
    channel = make_channel()
    captured = {}

    def _capture(request):
        captured.update(request.headers)
        return httpx.Response(200, json={"ok": True})

    with respx.mock:
        respx.post(URL).mock(side_effect=_capture)
        await channel.exec(
            user_id=99, session_id="s", step_id="s#1", tool="desk_window_list",
            arguments={}, timeout_ms=3000, evidence="user_request",
        )
    assert captured["x-user-id"] == "99"
    assert captured["x-agent-sig"].startswith("sha256=")
    assert captured["x-agent-ts"].isdigit()

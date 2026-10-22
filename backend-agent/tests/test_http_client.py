"""出站客户端复用策略的契约测试。

语音通话按句流式合成时，每句都是一次到 dashscope 的 POST。若每次都 `async with
new_client()`，连接池新建 → 重新 TCP + TLS 握手，白送 150-300ms 首包延迟。
所以「同一事件循环内按名字复用、跨循环自动重建」这条策略必须钉住。
"""

import asyncio

import pytest

from app.http_client import aclose_shared, new_client, shared_client


@pytest.fixture(autouse=True)
async def _cleanup():
    yield
    await aclose_shared()


async def test_same_name_returns_same_client():
    first = shared_client("dashscope-tts")
    second = shared_client("dashscope-tts")
    assert first is second
    assert not first.is_closed


async def test_different_names_are_isolated():
    a = shared_client("tts")
    b = shared_client("asr")
    assert a is not b


def test_rebuilds_when_event_loop_changes():
    """httpx.AsyncClient 绑定创建它的循环；跨循环复用会在首个请求上抛 RuntimeError。"""
    seen = []

    async def grab():
        seen.append(shared_client("loop-bound"))

    for _ in range(2):
        loop = asyncio.new_event_loop()
        try:
            loop.run_until_complete(grab())
        finally:
            loop.close()

    assert len(seen) == 2
    assert seen[0] is not seen[1], "新事件循环必须拿到新客户端"
    assert not seen[1].is_closed


async def test_rebuilds_after_close():
    client = shared_client("closed")
    await client.aclose()
    assert client.is_closed
    fresh = shared_client("closed")
    assert fresh is not client
    assert not fresh.is_closed


async def test_shared_client_keeps_outbound_policy():
    """复用不得绕过 new_client()：UA、超时、IPv4 绑定都还要在。"""
    client = shared_client("policy")
    assert client.headers["User-Agent"].startswith("LianYu-Agent/")
    assert client.timeout.connect == new_client().timeout.connect


def test_keepalive_window_outlives_call_gaps():
    """httpx 默认 keepalive_expiry=5s，而语音通话轮次间隔常态 5-20s。

    默认值等于「每轮都重新握手」——实测冷首包 2690ms vs 热 314ms。
    这条断言钉住「保活窗口必须显著大于一轮对话的间隔」。
    """
    from app.http_client import _SHARED_LIMITS

    assert _SHARED_LIMITS.keepalive_expiry >= 60.0
    assert _SHARED_LIMITS.max_keepalive_connections >= 2


async def test_aclose_shared_is_idempotent():
    shared_client("twice")
    await aclose_shared()
    await aclose_shared()

"""上游状态码 → 异常类型 → 是否计入熔断。

生产事故背景（2026-10-20）：用户自己的 key 失效（401）或欠费（402）被计入
`provider|baseUrl` 维度的熔断失败率，导致**同 provider 的其他正常用户**一起被
快失败（日志出现 `circuit open for Provider326`）。

规则：
- 4xx（429 除外）：用户侧问题 → `GatewayBusinessError` → 不计入熔断
- 5xx / 429：上游问题 → `ModelGatewayError` → 计入熔断

注意：这些断言必须打在**真实调用路径**（_do_post / _stream_once）上，
只测 `_raise_for_status` 会漏掉「抛了业务错但被网络层 except 重新包装」这类问题
（2026-10-20 实际踩到：两个路径都仍把 4xx 记成上游失败）。
"""

import httpx
import pytest

from app.gateway import ModelGateway, ModelGatewayError, _raise_for_status
from app.resilience import (
    CircuitState,
    GatewayBusinessError,
    breaker_registry,
    reset_breakers,
    reset_lanes,
)


@pytest.fixture(autouse=True)
def _reset():
    reset_breakers()
    reset_lanes()
    yield
    reset_breakers()
    reset_lanes()


def _client(status: int) -> httpx.AsyncClient:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"error": {"message": "nope"}})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


@pytest.mark.parametrize("status", [200, 201, 204])
def test_success_does_not_raise(status):
    _raise_for_status(status)


@pytest.mark.parametrize("status", [400, 401, 402, 403, 404, 422])
def test_client_errors_are_business_errors_not_circuit_failures(status):
    with pytest.raises(GatewayBusinessError):
        _raise_for_status(status)


def test_rate_limit_counts_as_upstream_failure():
    # 429 是上游限流，属于 provider 维度的问题，要计入熔断
    with pytest.raises(ModelGatewayError):
        _raise_for_status(429)


@pytest.mark.parametrize("status", [500, 502, 503, 504])
def test_server_errors_count_as_upstream_failure(status):
    with pytest.raises(ModelGatewayError):
        _raise_for_status(status)


# ── 真实路径：阻塞（_do_post） ──


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [400, 401, 402, 403, 404, 422])
async def test_blocking_path_maps_4xx_to_business_error(status):
    client = _client(status)
    try:
        gateway = ModelGateway(client=client)
        with pytest.raises(GatewayBusinessError):
            await gateway.chat_sync(
                base_url="https://fake/v1", api_key="k", model="m",
                system="s", user_text="u", temperature=0.7, max_tokens=8,
                provider="p")
    finally:
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [429, 500, 502, 503, 504])
async def test_blocking_path_maps_upstream_errors_to_model_error(status):
    client = _client(status)
    try:
        gateway = ModelGateway(client=client)
        with pytest.raises(ModelGatewayError) as exc_info:
            await gateway.chat_sync(
                base_url="https://fake/v1", api_key="k", model="m",
                system="s", user_text="u", temperature=0.7, max_tokens=8,
                provider="p")
        assert not isinstance(exc_info.value, GatewayBusinessError)
    finally:
        await client.aclose()


# ── 真实路径：流式（_stream_once） ──


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [401, 402, 404])
async def test_stream_path_maps_4xx_to_business_error_without_wrapping(status):
    """回归：业务错不能被 `except Exception` 重新包装成 ModelGatewayError。"""
    client = _client(status)
    try:
        gateway = ModelGateway(client=client)
        with pytest.raises(GatewayBusinessError):
            async for _ in gateway.stream_text(
                    base_url="https://fake/v1", api_key="k", model="m",
                    messages=[{"role": "user", "content": "u"}],
                    temperature=0.7, max_tokens=8, provider="p"):
                pass
    finally:
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [429, 500, 502])
async def test_stream_path_maps_upstream_errors_to_model_error(status):
    client = _client(status)
    try:
        gateway = ModelGateway(client=client)
        with pytest.raises(ModelGatewayError) as exc_info:
            async for _ in _stream_all(gateway):
                pass
        assert not isinstance(exc_info.value, GatewayBusinessError)
    finally:
        await client.aclose()


async def _stream_all(gateway):
    async for delta in gateway.stream_text(
            base_url="https://fake/v1", api_key="k", model="m",
            messages=[{"role": "user", "content": "u"}],
            temperature=0.7, max_tokens=8, provider="p"):
        yield delta


# ── 熔断器层面的最终判定 ──


@pytest.mark.asyncio
async def test_repeated_401_does_not_open_breaker():
    """10 个用户 key 失效不应把同 provider 的熔断器打开。"""
    for _ in range(10):
        client = _client(401)
        try:
            gateway = ModelGateway(client=client)
            with pytest.raises(GatewayBusinessError):
                await gateway.chat_sync(
                    base_url="https://fake/v1", api_key="k", model="m",
                    system="s", user_text="u", temperature=0.7, max_tokens=8,
                    provider="p")
        finally:
            await client.aclose()
    breaker = breaker_registry().resolve("p", "https://fake/v1")
    assert breaker.state is CircuitState.CLOSED


@pytest.mark.asyncio
async def test_repeated_500_does_open_breaker():
    """上游 5xx 仍应触发熔断（确认没有把真实上游故障也放过）。"""
    from app.resilience import GatewayRejectedError

    breaker = breaker_registry().resolve("p", "https://fake/v1")
    for _ in range(10):
        client = _client(500)
        try:
            gateway = ModelGateway(client=client)
            try:
                await gateway.chat_sync(
                    base_url="https://fake/v1", api_key="k", model="m",
                    system="s", user_text="u", temperature=0.7, max_tokens=8,
                    provider="p")
            except (ModelGatewayError, GatewayRejectedError):
                pass
        finally:
            await client.aclose()
        if breaker.state is CircuitState.OPEN:
            break
    assert breaker.state is CircuitState.OPEN

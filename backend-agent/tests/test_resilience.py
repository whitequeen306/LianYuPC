"""韧性层行为测试：熔断状态机 / 瞬断判定 / 隔舱快速失败。

对齐 Java `AiResilience` + `UpstreamCircuitBreakerFactory` 的语义。
"""

import asyncio

import httpx
import pytest

from app.resilience import (
    BreakerConfig,
    BreakerRegistry,
    CircuitState,
    GatewayBusinessError,
    GatewayRejectedError,
    LaneLimiter,
    UpstreamBreaker,
    breaker_key,
    call_with_transient_retry,
    is_transient_failure,
)


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


# ── key 归一化（对齐 UpstreamCircuitBreakerFactory.key）──


def test_breaker_key_normalizes_and_strips():
    assert breaker_key("DashScope", "https://api.example.com/v1/") == "ai-upstream|dashscope|https://api.example.com/v1"
    assert breaker_key("  ", "  ") is None
    assert breaker_key(None, None) is None


# ── 瞬断判定（对齐 isTransientStreamFailure）──


def test_transient_dns_and_refused():
    assert is_transient_failure(httpx.ConnectError("connection refused"))
    assert is_transient_failure(httpx.ConnectTimeout("connect timed out"))
    assert is_transient_failure(RuntimeError("temporary failure in name resolution"))
    assert is_transient_failure(RuntimeError("failed to resolve 'api.example.com'"))


def test_non_transient_not_retried():
    """读超时 / 5xx / 业务错都不算瞬断 —— 重试会重复生成、重复扣 token。"""
    assert not is_transient_failure(httpx.ReadTimeout("read timed out"))
    assert not is_transient_failure(RuntimeError("model gateway http 500"))
    assert not is_transient_failure(GatewayBusinessError("empty content"))


def test_transient_detection_walks_cause_chain():
    try:
        try:
            raise httpx.ConnectError("connection refused")
        except httpx.ConnectError as inner:
            raise RuntimeError("wrapped") from inner
    except RuntimeError as outer:
        assert is_transient_failure(outer)


# ── 重试策略（对齐 callBlockingWithTransientRetry：最多 2 次）──


@pytest.mark.asyncio
async def test_retry_once_on_transient_then_succeed():
    attempts = 0

    async def flaky():
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ConnectError("connection refused")
        return "ok"

    assert await call_with_transient_retry(flaky) == "ok"
    assert attempts == 2


@pytest.mark.asyncio
async def test_no_retry_on_business_error():
    attempts = 0

    async def failing():
        nonlocal attempts
        attempts += 1
        raise GatewayBusinessError("provider rejected")

    with pytest.raises(GatewayBusinessError):
        await call_with_transient_retry(failing)
    assert attempts == 1


@pytest.mark.asyncio
async def test_retry_gives_up_after_max_attempts():
    attempts = 0

    async def always_transient():
        nonlocal attempts
        attempts += 1
        raise httpx.ConnectTimeout("connect timed out")

    with pytest.raises(httpx.ConnectTimeout):
        await call_with_transient_retry(always_transient)
    assert attempts == 2


# ── 熔断状态机 ──


def fast_breaker(clock) -> UpstreamBreaker:
    return UpstreamBreaker(
        "test",
        BreakerConfig(
            sliding_window_size=20,
            minimum_number_of_calls=5,
            failure_rate_threshold=50.0,
            wait_duration_seconds=30.0,
            permitted_calls_in_half_open=3,
        ),
        clock=clock,
    )


@pytest.mark.asyncio
async def test_breaker_opens_after_threshold():
    clock = FakeClock()
    breaker = fast_breaker(clock)
    for _ in range(5):
        await breaker.on_error(RuntimeError("boom"))
    assert breaker.state is CircuitState.OPEN


@pytest.mark.asyncio
async def test_breaker_stays_closed_below_min_calls():
    clock = FakeClock()
    breaker = fast_breaker(clock)
    for _ in range(4):  # 不足 5 个样本
        await breaker.on_error(RuntimeError("boom"))
    assert breaker.state is CircuitState.CLOSED


@pytest.mark.asyncio
async def test_breaker_business_error_counts_as_success():
    """业务错不污染失败率（对齐 releaseUpstreamPermit 的 cause 链判定）。"""
    clock = FakeClock()
    breaker = fast_breaker(clock)
    for _ in range(10):
        await breaker.on_error(GatewayBusinessError("内容为空"))
    assert breaker.state is CircuitState.CLOSED


@pytest.mark.asyncio
async def test_breaker_half_open_recovers_after_wait():
    clock = FakeClock()
    breaker = fast_breaker(clock)
    for _ in range(5):
        await breaker.on_error(RuntimeError("boom"))
    assert breaker.state is CircuitState.OPEN
    assert await breaker.try_acquire() is False  # 冷却期内快速失败

    clock.advance(30)
    assert await breaker.try_acquire() is True   # 进入半开
    assert breaker.state is CircuitState.HALF_OPEN
    for _ in range(3):
        await breaker.on_success()
    assert breaker.state is CircuitState.CLOSED


@pytest.mark.asyncio
async def test_breaker_half_open_failure_reopens():
    clock = FakeClock()
    breaker = fast_breaker(clock)
    for _ in range(5):
        await breaker.on_error(RuntimeError("boom"))
    clock.advance(31)
    await breaker.try_acquire()
    await breaker.on_error(RuntimeError("still down"))
    assert breaker.state is CircuitState.OPEN


@pytest.mark.asyncio
async def test_half_open_limits_permits():
    clock = FakeClock()
    breaker = fast_breaker(clock)
    for _ in range(5):
        await breaker.on_error(RuntimeError("boom"))
    clock.advance(31)
    assert await breaker.try_acquire() is True   # 1
    assert await breaker.try_acquire() is True   # 2
    assert await breaker.try_acquire() is True   # 3
    assert await breaker.try_acquire() is False  # 半开许可用尽


# ── 注册表按上游隔离 ──


def test_registry_isolates_upstreams():
    registry = BreakerRegistry()
    a = registry.resolve("dashscope", "https://a.example.com")
    b = registry.resolve("dashscope", "https://b.example.com")
    assert a is not b
    assert registry.resolve("DashScope", "https://A.example.com/") is a  # 归一化命中同一实例


def test_registry_falls_back_when_blank():
    registry = BreakerRegistry()
    assert registry.resolve(None, None) is registry.fallback


# ── 隔舱 ──


@pytest.mark.asyncio
async def test_lane_limiter_fast_fails_when_full():
    limiter = LaneLimiter("test-lane", max_concurrent=1, max_wait_seconds=0.05)
    async with limiter:
        with pytest.raises(GatewayRejectedError):
            async with limiter:
                pass


@pytest.mark.asyncio
async def test_lane_limiter_releases_on_error():
    limiter = LaneLimiter("test-lane-2", max_concurrent=1, max_wait_seconds=0.05)
    with pytest.raises(RuntimeError):
        async with limiter:
            raise RuntimeError("inner failure")
    # 异常后许可必须归还，否则下一次会误判满载
    async with limiter:
        pass

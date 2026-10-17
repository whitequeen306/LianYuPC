"""出站韧性层 —— Java `AiResilience` / `UpstreamCircuitBreakerFactory` 的 Python 等价物。

移植对照（backend/lianyu-service/.../ai/）：

| Java | 本模块 | 语义 |
|---|---|---|
| `UpstreamCircuitBreakerFactory.key` | `breaker_key()` | `ai-upstream` + provider + baseUrl，小写、去首尾空白与尾斜杠 |
| `MAX_UPSTREAM_BREAKERS = 500` | 同常量 | 超限回落全局 `ai-chat` |
| `CircuitBreaker`（COUNT_BASED 20 窗口 / 5 最小样本 / 50% / 30s / 半开 3 次） | `UpstreamBreaker` | 见类注释 |
| `Bulkhead` ai-chat 80 / ai-background 6 | `LaneLimiter` | 占槽上限 + 快速失败（不排队太久） |
| `isTransientStreamFailure` | `is_transient_failure()` | 只有 DNS / 拒绝 / 连接超时才重试 |
| `releaseUpstreamPermit` 里 BusinessException 记成功 | `BusinessError` | 业务错不污染熔断失败率 |

设计约束（红线）：
- 熔断/隔舱只影响**出站调用**，不引入任何阻塞等待（`asyncio` 原生，无线程）
- 业务错误（模型拒绝、参数错、内容为空）**永远不重试**——非幂等，重试会重复扣 token
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import deque
from dataclasses import dataclass, field
from enum import Enum
from typing import Awaitable, Callable, TypeVar

import httpx

from app.config import get_settings

logger = logging.getLogger(__name__)

T = TypeVar("T")

FALLBACK_BREAKER_NAME = "ai-chat"
MAX_UPSTREAM_BREAKERS = 500


class GatewayError(Exception):
    """模型网关错误基类。"""


class GatewayBusinessError(GatewayError):
    """业务错：不计入熔断失败率（对齐 Java BusinessException 语义）。"""


class GatewayRejectedError(GatewayError):
    """熔断打开 / 隔舱满：快速失败，不等待。"""


class CircuitState(Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass(frozen=True)
class BreakerConfig:
    """对齐 resilience4j 的 ai-chat 模板参数（application.yml:519-524）。"""

    sliding_window_size: int = 20
    minimum_number_of_calls: int = 5
    failure_rate_threshold: float = 50.0
    wait_duration_seconds: float = 30.0
    permitted_calls_in_half_open: int = 3


class UpstreamBreaker:
    """按上游（provider + baseUrl）隔离的熔断器。

    与 resilience4j 的差异（有意为之，已在测试中固化）：
    - 半开状态任一次失败即重新打开（resilience4j 是「按已完成的 permit 样本算失败率」，
      对逐个上游的断路器而言，提前失败更安全，也更容易解释）
    - 半开状态 N 次连续成功即关闭（resilience4j 同样会在达到样本数后关闭）
    """

    def __init__(self, name: str, config: BreakerConfig | None = None, *, clock=time.monotonic):
        self.name = name
        self._config = config or BreakerConfig()
        self._clock = clock
        self._state = CircuitState.CLOSED
        self._opened_at: float | None = None
        self._results: deque[bool] = deque(maxlen=self._config.sliding_window_size)
        self._half_open_inflight = 0
        self._half_open_successes = 0
        self._lock = asyncio.Lock()

    @property
    def state(self) -> CircuitState:
        return self._state

    async def try_acquire(self) -> bool:
        """尝试获取一次调用许可；False 表示应快速失败。"""
        async with self._lock:
            if self._state is CircuitState.CLOSED:
                return True
            if self._state is CircuitState.OPEN:
                assert self._opened_at is not None
                if self._clock() - self._opened_at >= self._config.wait_duration_seconds:
                    self._to_half_open()
                    self._half_open_inflight = 1
                    return True
                return False
            # HALF_OPEN
            if self._half_open_inflight < self._config.permitted_calls_in_half_open:
                self._half_open_inflight += 1
                return True
            return False

    async def on_success(self) -> None:
        async with self._lock:
            if self._state is CircuitState.HALF_OPEN:
                self._half_open_successes += 1
                self._half_open_inflight = max(0, self._half_open_inflight - 1)
                if self._half_open_successes >= self._config.permitted_calls_in_half_open:
                    self._close()
                return
            if self._state is CircuitState.CLOSED:
                self._results.append(True)
                self._maybe_open()

    async def on_error(self, error: BaseException | None = None) -> None:
        async with self._lock:
            if isinstance(error, GatewayBusinessError):
                # 业务错按成功记录（对齐 Java releaseUpstreamPermit 的 cause 链判定）
                if self._state is CircuitState.HALF_OPEN:
                    self._half_open_successes += 1
                    self._half_open_inflight = max(0, self._half_open_inflight - 1)
                    if self._half_open_successes >= self._config.permitted_calls_in_half_open:
                        self._close()
                elif self._state is CircuitState.CLOSED:
                    self._results.append(True)
                    self._maybe_open()
                return

            if self._state is CircuitState.HALF_OPEN:
                self._half_open_inflight = max(0, self._half_open_inflight - 1)
                self._open()
                return
            if self._state is CircuitState.CLOSED:
                self._results.append(False)
                self._maybe_open()

    # ── 内部状态迁移 ──

    def _maybe_open(self) -> None:
        if len(self._results) < self._config.minimum_number_of_calls:
            return
        failures = sum(1 for ok in self._results if not ok)
        rate = failures * 100.0 / len(self._results)
        if rate >= self._config.failure_rate_threshold:
            logger.warning(
                "breaker %s OPEN (failure_rate=%.0f%%, samples=%d)",
                self.name, rate, len(self._results),
            )
            self._open()

    def _open(self) -> None:
        self._state = CircuitState.OPEN
        self._opened_at = self._clock()

    def _to_half_open(self) -> None:
        self._state = CircuitState.HALF_OPEN
        self._half_open_inflight = 0
        self._half_open_successes = 0

    def _close(self) -> None:
        self._state = CircuitState.CLOSED
        self._opened_at = None
        self._results.clear()
        self._half_open_inflight = 0
        self._half_open_successes = 0


def breaker_key(provider: str | None, base_url: str | None) -> str | None:
    """对齐 Java `UpstreamCircuitBreakerFactory.key`：两者皆空返回 None（用全局兜底）。"""
    prov = (provider or "").strip().lower()
    base = (base_url or "").strip().lower()
    base = base.rstrip("/")
    if not prov and not base:
        return None
    return f"ai-upstream|{prov}|{base}"


class BreakerRegistry:
    """breaker 实例表；超过 MAX_UPSTREAM_BREAKERS 回落全局 breaker。"""

    def __init__(self, config: BreakerConfig | None = None, *, clock=time.monotonic):
        self._config = config or BreakerConfig()
        self._clock = clock
        self._breakers: dict[str, UpstreamBreaker] = {}
        self._fallback = UpstreamBreaker(FALLBACK_BREAKER_NAME, self._config, clock=clock)

    def resolve(self, provider: str | None, base_url: str | None) -> UpstreamBreaker:
        key = breaker_key(provider, base_url)
        if key is None:
            return self._fallback
        if key not in self._breakers and len(self._breakers) >= MAX_UPSTREAM_BREAKERS:
            return self._fallback
        breaker = self._breakers.get(key)
        if breaker is None:
            breaker = UpstreamBreaker(key, self._config, clock=self._clock)
            self._breakers[key] = breaker
        return breaker

    @property
    def fallback(self) -> UpstreamBreaker:
        return self._fallback


@dataclass
class LaneLimiter:
    """隔舱（bulkhead）：限制同时占槽数，超时即快速失败。

    对齐 resilience4j bulkhead 的 `max-wait-duration`：等待超过预算就拒绝，
    而不是把请求堆在队列里（堆队列在慢上游场景等于把线程/连接抵押出去）。
    """

    name: str
    max_concurrent: int
    max_wait_seconds: float
    _semaphore: asyncio.Semaphore = field(init=False, repr=False)

    def __post_init__(self) -> None:
        self._semaphore = asyncio.Semaphore(self.max_concurrent)

    async def __aenter__(self) -> "LaneLimiter":
        try:
            await asyncio.wait_for(self._semaphore.acquire(), timeout=self.max_wait_seconds)
        except asyncio.TimeoutError as exc:
            raise GatewayRejectedError(
                f"{self.name} bulkhead full ({self.max_concurrent} slots)"
            ) from exc
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        self._semaphore.release()


_lanes: dict[str, LaneLimiter] = {}


def lane(name: str) -> LaneLimiter:
    """取（或惰性创建）一条 lane：interactive / background。"""
    limiter = _lanes.get(name)
    if limiter is None:
        settings = get_settings()
        if name == "background":
            limiter = LaneLimiter("ai-background", settings.bulkhead_background, settings.bulkhead_background_wait)
        else:
            limiter = LaneLimiter("ai-chat", settings.bulkhead_interactive, settings.bulkhead_interactive_wait)
        _lanes[name] = limiter
    return limiter


def reset_lanes() -> None:
    """测试用：清空 lane 单例。"""
    _lanes.clear()


_TRANSIENT_MARKERS = (
    "failed to resolve",
    "temporary failure in name resolution",
    "connection refused",
    "connect timed out",
    "connect timeout",
    "nodename nor servname",
)


def is_transient_failure(error: BaseException) -> bool:
    """对齐 Java `isTransientStreamFailure`：只有 DNS / 拒绝 / 连接超时才可重试。

    注意：**读超时不重试**（对端已建立连接但慢返回，重试会重复生成、重复扣 token）。
    """
    seen: set[int] = set()
    current: BaseException | None = error
    depth = 0
    while current is not None and depth < 6 and id(current) not in seen:
        seen.add(id(current))
        depth += 1
        if isinstance(current, (httpx.ConnectError, httpx.ConnectTimeout)):
            # ConnectError 覆盖 DNS 失败与 connection refused；读/写超时是别的类型
            return True
        message = str(current).lower()
        if any(marker in message for marker in _TRANSIENT_MARKERS):
            return True
        current = current.__cause__ or current.__context__
    return False


async def call_with_transient_retry(
    operation: Callable[[], Awaitable[T]],
    *,
    max_attempts: int = 2,
    label: str = "gateway",
) -> T:
    """瞬断重试：仅在 `is_transient_failure` 为真时重试，最多 `max_attempts` 次。

    对齐 Java `callBlockingWithTransientRetry`（maxAttempts=2，仅连接类异常）。
    """
    last: BaseException | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            return await operation()
        except BaseException as exc:  # noqa: BLE001 - 需要按类型分类后再抛
            last = exc
            if attempt >= max_attempts or not is_transient_failure(exc):
                raise
            logger.warning(
                "%s transient connect failure, retrying (%d/%d): %s",
                label, attempt, max_attempts, exc,
            )
    assert last is not None
    raise last


_breaker_registry: BreakerRegistry | None = None


def breaker_registry() -> BreakerRegistry:
    global _breaker_registry
    if _breaker_registry is None:
        _breaker_registry = BreakerRegistry()
    return _breaker_registry


def reset_breakers() -> None:
    """测试用：清空注册表。"""
    global _breaker_registry
    _breaker_registry = None


__all__ = [
    "BreakerConfig",
    "BreakerRegistry",
    "CircuitState",
    "FALLBACK_BREAKER_NAME",
    "GatewayBusinessError",
    "GatewayError",
    "GatewayRejectedError",
    "LaneLimiter",
    "MAX_UPSTREAM_BREAKERS",
    "UpstreamBreaker",
    "breaker_key",
    "breaker_registry",
    "call_with_transient_retry",
    "is_transient_failure",
    "lane",
    "reset_breakers",
    "reset_lanes",
]

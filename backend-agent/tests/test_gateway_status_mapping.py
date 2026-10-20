"""上游状态码 → 异常类型 → 是否计入熔断。

生产事故背景（2026-10-20）：用户自己的 key 失效（401）或欠费（402）被计入
`provider|baseUrl` 维度的熔断失败率，导致**同 provider 的其他正常用户**一起被
快失败（日志出现 `circuit open for Provider326`）。

规则：
- 4xx（429 除外）：用户侧问题 → `GatewayBusinessError` → 不计入熔断
- 5xx / 429：上游问题 → `ModelGatewayError` → 计入熔断
"""

import pytest

from app.gateway import ModelGatewayError, _raise_for_status
from app.resilience import GatewayBusinessError


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


def test_business_error_is_not_counted_by_breaker():
    """确认熔断器把 GatewayBusinessError 当成功，不会因用户 key 失效而打开。"""
    assert issubclass(GatewayBusinessError, Exception)
    assert not issubclass(GatewayBusinessError, ModelGatewayError)

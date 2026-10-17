"""出站 HTTP 客户端工厂。

对齐 Java 侧 SsrfPinningClientFactory 红线：
- 一切出站调用显式带超时（connect 15s / read 60s / write 30s）
- 禁止任何「等别人」的调用使用零超时客户端
"""

import httpx

from app.config import get_settings


def outbound_timeout() -> httpx.Timeout:
    connect, read, write, pool = get_settings().outbound_timeout
    return httpx.Timeout(connect=connect, read=read, write=write, pool=pool)


def new_client(base_url: str | None = None) -> httpx.AsyncClient:
    """唯一合法的出站 AsyncClient 构造入口。"""
    return httpx.AsyncClient(
        base_url=base_url,
        timeout=outbound_timeout(),
        # DashScope AAAA 兼容：对齐 Java -Djava.net.preferIPv4Addresses=true
        transport=httpx.AsyncHTTPTransport(local_address="0.0.0.0"),
    )

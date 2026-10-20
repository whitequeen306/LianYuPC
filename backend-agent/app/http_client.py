"""出站 HTTP 客户端工厂。

对齐 Java 侧 SsrfPinningClientFactory 红线：
- 一切出站调用显式带超时（connect 15s / read 60s / write 30s）
- 禁止任何「等别人」的调用使用零超时客户端
"""

import httpx

from app.config import get_settings

# 生产实测（2026-10-20）：部分代理站（如 linzihan.fun）的 WAF 会按 UA 拦截
# httpx 默认 UA（`python-httpx/x.y.z`），返回 404「Not Found」——即使路径完全正确。
# 用固定、诚实的客户端标识即可通过（curl / urllib / OpenAI SDK / 浏览器 UA 均正常）。
USER_AGENT = "LianYu-Agent/1.0 (+https://downloadlianyu.asia)"


def outbound_timeout() -> httpx.Timeout:
    connect, read, write, pool = get_settings().outbound_timeout
    return httpx.Timeout(connect=connect, read=read, write=write, pool=pool)


def new_client(base_url: str | None = None) -> httpx.AsyncClient:
    """唯一合法的出站 AsyncClient 构造入口。

    注意：base_url 为空时必须**不传**该参数（httpx 不接受显式 None）。
    """
    kwargs: dict = {
        "timeout": outbound_timeout(),
        "headers": {"User-Agent": USER_AGENT},
        # DashScope AAAA 兼容：对齐 Java -Djava.net.preferIPv4Addresses=true
        "transport": httpx.AsyncHTTPTransport(local_address="0.0.0.0"),
    }
    if base_url:
        kwargs["base_url"] = base_url
    return httpx.AsyncClient(**kwargs)

"""出站 HTTP 客户端工厂。

对齐 Java 侧 SsrfPinningClientFactory 红线：
- 一切出站调用显式带超时（connect 15s / read 60s / write 30s）
- 禁止任何「等别人」的调用使用零超时客户端
"""

import asyncio

import httpx

from app.config import get_settings

# 生产实测（2026-10-20）：部分代理站（如 linzihan.fun）的 WAF 会按 UA 拦截
# httpx 默认 UA（`python-httpx/x.y.z`），返回 404「Not Found」——即使路径完全正确。
# 用固定、诚实的客户端标识即可通过（curl / urllib / OpenAI SDK / 浏览器 UA 均正常）。
USER_AGENT = "LianYu-Agent/1.0 (+https://downloadlianyu.asia)"


def outbound_timeout() -> httpx.Timeout:
    connect, read, write, pool = get_settings().outbound_timeout
    return httpx.Timeout(connect=connect, read=read, write=write, pool=pool)


def new_client(base_url: str | None = None, limits: httpx.Limits | None = None) -> httpx.AsyncClient:
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
    if limits is not None:
        kwargs["limits"] = limits
    return httpx.AsyncClient(**kwargs)


# 按名字复用的长连接客户端。
# 值里存创建它的事件循环：httpx.AsyncClient 绑定在创建时的循环上，跨循环复用会炸。
_SHARED: dict[str, tuple[asyncio.AbstractEventLoop, httpx.AsyncClient]] = {}

# httpx 默认 keepalive_expiry 只有 5 秒。语音通话的轮次间隔常态是 5-20 秒，
# 用默认值等于**每轮都重新握手**（境内到阿里云 300-400ms，实测冷首包 2690ms vs 热 314ms）。
# 复用客户端存在的意义就是消掉这笔开销，所以显式把保活窗口拉到 3 分钟。
_SHARED_LIMITS = httpx.Limits(
    max_connections=16,
    max_keepalive_connections=8,
    keepalive_expiry=180.0,
)


def shared_client(name: str = "default") -> httpx.AsyncClient:
    """取一条按名字复用的客户端（**调用方不要关闭它**）。

    为什么需要：语音通话按句流式合成时，每句都是一次到 dashscope 的 POST。
    每次 `async with new_client()` 都会新建连接池 → 重新 TCP + TLS 握手（境内到阿里云
    约 150-300ms），一句一次，纯属白送的首包延迟。复用后只有第一句付出握手成本。

    仍然走 {@link new_client} 构造：出站策略（超时 / UA / IPv4 绑定）只保留一份实现，
    不允许绕过。
    """
    loop = asyncio.get_running_loop()
    cached = _SHARED.get(name)
    if cached is not None:
        cached_loop, client = cached
        if cached_loop is loop and not client.is_closed:
            return client
    client = new_client(limits=_SHARED_LIMITS)
    _SHARED[name] = (loop, client)
    return client


async def aclose_shared() -> None:
    """进程退出 / 测试收尾用：关掉所有复用客户端。"""
    for _, client in list(_SHARED.values()):
        try:
            await client.aclose()
        except Exception:  # noqa: BLE001 - 收尾失败无所谓
            pass
    _SHARED.clear()
